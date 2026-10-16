import com.sun.source.tree.*;
import com.sun.source.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

/**
 * HybridChain audit — Phase A mechanical inventory.
 *
 * Parses AND attributes every source file under src/main/java and src/test/java with the
 * JDK's own compiler (com.sun.source / JavacTask), so every call edge is resolved to its
 * real declaration rather than guessed from names.
 *
 * Usage:  java audit/tools/Inventory.java <projectDir>
 * Reads:  audit/out/cp-test.txt (mvn dependency:build-classpath -Dmdep.includeScope=test)
 * Writes: audit/inventory/{files,methods,tests,config,callgraph,env}.csv
 *
 * ── HEURISTICS (documented so the classification itself is auditable) ─────────────
 * Members      : methods, constructors, static/instance initializer blocks, and every field
 *                initializer that is not a compile-time literal (it runs logic at class or
 *                instance init). Members of inner, local and anonymous classes are rows of
 *                their own (owner = binary name, e.g. Outer$1).
 * Lambdas      : graph-only nodes (Outer#enclosing$lambda@LINE). Their code is reviewed as
 *                part of the enclosing member, but they get their own edges so a lambda
 *                registered as a network handler is an entry point in its own right.
 * CC           : 1 + if, for, foreach, while, do, non-default case label, catch, ?:, &&, ||.
 * Entry kinds  : main | rest:VERB path | coap | mqtt | websocket | filter | interceptor |
 *                spring-bean | jackson | p2p-ingress | p2p-handler | scheduled | async |
 *                framework-override.
 *   UNTRUSTED  : rest, coap, mqtt, websocket, filter, interceptor, jackson, p2p-ingress,
 *                p2p-handler.
 * touches_crypto : references java.security, javax.crypto, javax.net.ssl, org.bouncycastle,
 *                io.jsonwebtoken, or project Crypto/QuantumResistantCrypto/ZKProofSystem/
 *                CertificateAuthority/SSLUtils/PUFIdentityProvider/JwtManager.
 * touches_io   : java.io, java.nio.file, java.nio.channels, java.net, javax.net, LevelDB,
 *                Paho, Californium, java.sql, project Storage.
 * touches_state: writes a field (=, op=, ++/--), calls a mutator (put, add, remove, clear,
 *                computeX, merge, offer, poll, set, replaceX) on a field receiver, or calls a
 *                mutator-named method on AccountState/UTXOSet/Mempool/TokenRegistry/Storage/
 *                Blockchain/DeviceLifecycleManager/SSIManager.
 * sync         : synchronized modifier/block, or lock()/unlock()/tryLock() on a Lock.
 * nondeterminism: time (currentTimeMillis/nanoTime/Instant.now/new Date()), random
 *                (java.util.Random, Math.random, ThreadLocalRandom), charset (no-arg
 *                getBytes(), new String(byte[]) without charset), locale (String.format,
 *                toLowerCase/toUpperCase without Locale), hash-iteration (iterating a
 *                HashMap/HashSet/ConcurrentHashMap view), parallel (parallelStream/
 *                parallel()), float (double/float arithmetic in the member).
 * dead         : no inbound edge from main code, not an entry, not a framework override.
 * test_only    : inbound edges exist only from test code.
 */
public class Inventory {

    // ─── model ────────────────────────────────────────────────────────────────
    static final class Member {
        String id, owner, name, kind, signature, visibility = "", file, scope;
        int startLine, endLine, cc = 1;
        boolean crypto, io, state, sync;
        final Set<String> entry = new TreeSet<>();
        final Set<String> nondet = new TreeSet<>();
        final Set<String> calls = new LinkedHashSet<>();
        final Set<String> configReads = new TreeSet<>();
        boolean graphOnly;           // lambda nodes
        // test-only metrics
        int assertions;
        final Set<String> assertKinds = new TreeSet<>();
        final Set<String> mocks = new TreeSet<>();
        final Set<String> sysProps = new TreeSet<>();
        final Set<String> wiring = new TreeSet<>();
        boolean sleeps;
        String bodyHash = "";
        final List<String> annotations = new ArrayList<>();
    }

    static Elements elements;
    static Types types;
    static Trees trees;
    static final Map<String, Member> members = new LinkedHashMap<>();
    static final Map<String, Set<String>> overrideEdges = new HashMap<>();
    static final List<String[]> envReads = new ArrayList<>();      // key, member, how
    static final Map<String, String[]> configFields = new LinkedHashMap<>(); // name -> type,final,static,init,key
    static final Set<String> jacksonTargets = new TreeSet<>();
    static final Map<String, Set<String>> FIELD_MOCKS = new HashMap<>();
    static final Set<String> jacksonSerialized = new TreeSet<>();      // classes passed as X.class to readValue/convertValue
    static final Set<String> PROJECT_PREFIXES = Set.of("com.hybrid");
    static final Set<String> UNTRUSTED = Set.of("rest", "coap", "mqtt", "websocket", "filter",
            "interceptor", "jackson", "p2p-ingress", "p2p-handler");

    public static void main(String[] args) throws Exception {
        Path proj = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path inv = proj.resolve("audit/inventory");
        Files.createDirectories(inv);
        String cp = Files.readString(proj.resolve("audit/out/cp-test.txt")).trim();

        List<Path> mainSrc = javaFiles(proj.resolve("src/main/java"));
        List<Path> testSrc = javaFiles(proj.resolve("src/test/java"));
        writeFiles(proj, inv, mainSrc, testSrc);

        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, StandardCharsets.UTF_8);
        List<Path> all = new ArrayList<>(mainSrc);
        all.addAll(testSrc);
        Path classOut = Files.createTempDirectory("inv-classes");
        JavacTask task = (JavacTask) jc.getTask(null, fm, diags,
                List.of("-classpath", cp, "-proc:none", "-encoding", "UTF-8", "--release", "17",
                        "-Xlint:none", "-implicit:none", "-d", classOut.toString()),
                null, fm.getJavaFileObjectsFromPaths(all));
        Iterable<? extends CompilationUnitTree> cus = task.parse();
        task.analyze();
        long errors = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        System.out.println("attribution errors: " + errors);
        diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).limit(10)
                .forEach(d -> System.out.println("  " + d.getMessage(Locale.ROOT)));

        elements = task.getElements();
        types = task.getTypes();
        trees = Trees.instance(task);

        for (CompilationUnitTree cu : cus) {
            String file = proj.relativize(Paths.get(cu.getSourceFile().toUri())).toString().replace('\\', '/');
            String scope = file.startsWith("src/test") ? "test" : "main";
            new Scanner(cu, file, scope).scan(cu, null);
        }

        for (Member m : members.values()) {
            if ("main".equals(m.scope) && m.kind != null && m.kind.equals("method") && jacksonSerialized.contains(m.owner)
                    && (m.name.startsWith("get") || m.name.startsWith("is"))) m.entry.add("jackson-serialize");
        }
        // jackson-reflective entries: no-arg ctors and setters of deserialised classes
        for (Member m : members.values()) {
            if (!"main".equals(m.scope)) continue;
            if (jacksonTargets.contains(m.owner)
                    && (m.name.equals("<init>") || (m.name.startsWith("set") && m.kind.equals("method")))) {
                m.entry.add("jackson");
            }
        }

        writeOutputs(inv);
        System.out.println("members(main)=" + members.values().stream().filter(m -> !m.graphOnly && m.scope.equals("main")).count()
                + " tests=" + members.values().stream().filter(m -> m.entry.contains("test")).count()
                + " lambdas=" + members.values().stream().filter(m -> m.graphOnly).count());
    }

    // ─── scanner ──────────────────────────────────────────────────────────────
    static final class Scanner extends TreePathScanner<Void, Void> {
        final CompilationUnitTree cu;
        final String file, scope;
        final LineMap lines;
        final SourcePositions pos;
        final Deque<Member> stack = new ArrayDeque<>();
        final Deque<TypeElement> classStack = new ArrayDeque<>();
        int initCounter = 0;

        Scanner(CompilationUnitTree cu, String file, String scope) {
            this.cu = cu;
            this.file = file;
            this.scope = scope;
            this.lines = cu.getLineMap();
            this.pos = trees.getSourcePositions();
        }

        int line(Tree t, boolean end) {
            long p = end ? pos.getEndPosition(cu, t) : pos.getStartPosition(cu, t);
            return p < 0 ? -1 : (int) lines.getLineNumber(p);
        }

        Member open(String id, String owner, String name, String kind, String sig, Tree t) {
            Member m = members.computeIfAbsent(id, k -> new Member());
            m.id = id;
            m.owner = owner;
            m.name = name;
            m.kind = kind;
            m.signature = sig;
            m.file = file;
            m.scope = scope;
            m.startLine = line(t, false);
            m.endLine = line(t, true);
            return m;
        }

        @Override
        public Void visitClass(ClassTree ct, Void v) {
            Element e = trees.getElement(getCurrentPath());
            if (!(e instanceof TypeElement)) return super.visitClass(ct, v);
            TypeElement te = (TypeElement) e;
            classStack.push(te);
            String owner = bin(te);
            Deque<Member> saved = new ArrayDeque<>(stack);
            stack.clear();
            int savedInit = initCounter;
            initCounter = 0;
            for (Tree mt : ct.getMembers()) {
                if (mt instanceof BlockTree) {
                    BlockTree b = (BlockTree) mt;
                    String nm = b.isStatic() ? "<clinit>" : "<init-block>";
                    Member m = open(owner + "#" + nm + "#" + (++initCounter), owner, nm,
                            b.isStatic() ? "static-init" : "instance-init", nm, mt);
                    stack.push(m);
                    scan(mt, v);
                    stack.pop();
                } else if (mt instanceof VariableTree) {
                    VariableTree vt = (VariableTree) mt;
                    if (owner.endsWith(".Config")) recordConfigField(te, vt);
                    if ("test".equals(scope)) {
                        for (AnnotationTree a : vt.getModifiers().getAnnotations()) {
                            String an = a.getAnnotationType().toString();
                            if (an.endsWith("Mock") || an.endsWith("Spy") || an.endsWith("MockBean") || an.endsWith("SpyBean")) {
                                FIELD_MOCKS.computeIfAbsent(owner, k -> new TreeSet<>()).add(vt.getType().toString());
                            }
                        }
                    }
                    if (vt.getInitializer() != null && !isConstant(vt.getInitializer())) {
                        String nm = vt.getName().toString();
                        Member m = open(owner + "#field-init:" + nm, owner, nm,
                                hasFunctional(vt.getInitializer()) ? "field-init-lambda" : "field-init",
                                "field " + vt.getType() + " " + nm, mt);
                        m.visibility = visibility(vt.getModifiers());
                        if (vt.getModifiers().getFlags().contains(Modifier.STATIC) || te.getKind() == ElementKind.INTERFACE
                                || te.getKind() == ElementKind.ENUM && isEnumConstant(te, vt)) STATIC_FIELDS.add(m.id);
                        stack.push(m);
                        scan(mt, v);
                        stack.pop();
                    }
                } else if (mt instanceof MethodTree) {
                    scan(mt, v);
                } else if (mt instanceof ClassTree) {
                    scan(mt, v);
                }
            }
            initCounter = savedInit;
            stack.clear();
            stack.addAll(saved);
            classStack.pop();
            return null;
        }

        @Override
        public Void visitMethod(MethodTree mt, Void v) {
            Element e = trees.getElement(getCurrentPath());
            if (!(e instanceof ExecutableElement)) return null;
            ExecutableElement ee = (ExecutableElement) e;
            String id = idOf(ee);
            TypeElement owner = (TypeElement) ee.getEnclosingElement();
            boolean ctor = ee.getKind() == ElementKind.CONSTRUCTOR;
            Member m = open(id, bin(owner), ctor ? "<init>" : ee.getSimpleName().toString(),
                    ctor ? "constructor" : "method", signature(ee), mt);
            m.visibility = visibility(mt.getModifiers());
            if (mt.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)) m.sync = true;
            for (AnnotationTree a : mt.getModifiers().getAnnotations()) m.annotations.add(a.toString());
            classifyEntry(m, ee, owner, mt);
            recordOverrides(ee, owner, m);
            if (mt.getBody() != null) m.bodyHash = hash(mt.getBody().toString());
            stack.push(m);
            super.visitMethod(mt, v);
            stack.pop();
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lt, Void v) {
            Member parent = stack.peek();
            if (parent == null) return super.visitLambdaExpression(lt, v);
            Member lam = lambdaNode(parent, lt);
            String reg = registrationKind(getCurrentPath());
            if (reg != null) lam.entry.add(reg);
            stack.push(lam);
            super.visitLambdaExpression(lt, v);
            stack.pop();
            return null;
        }

        Member lambdaNode(Member parent, Tree t) {
            String id = parent.id + "$lambda@" + line(t, false) + ":" + pos.getStartPosition(cu, t);
            Member lam = open(id, parent.owner, parent.name + "$lambda", "lambda", "lambda", t);
            lam.graphOnly = true;
            parent.calls.add(id);
            return lam;
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree mr, Void v) {
            Member cur = stack.peek();
            Element e = trees.getElement(getCurrentPath());
            if (cur != null && e instanceof ExecutableElement && isProject(e)) {
                String target = idOf((ExecutableElement) e);
                String reg = registrationKind(getCurrentPath());
                if (reg != null) {
                    // method reference registered as handler/task: the TARGET becomes an entry
                    Member tm = members.computeIfAbsent(target, k -> new Member());
                    tm.entry.add(reg);
                }
                cur.calls.add(target);
            }
            classifyRef(cur, e);
            return super.visitMemberReference(mr, v);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree mi, Void v) {
            Member cur = stack.peek();
            Element e = trees.getElement(getCurrentPath());
            String name = methodName(mi);
            if (cur != null) {
                if (e instanceof ExecutableElement) {
                    ExecutableElement ee = (ExecutableElement) e;
                    if (isProject(ee)) cur.calls.add(idOf(ee));
                    classifyRef(cur, ee);
                    classifyCall(cur, ee, mi, name);
                }
                countAssertion(cur, name, mi);
                recordEnv(cur, mi, name);
                recordJacksonTarget(mi, name);
            }
            return super.visitMethodInvocation(mi, v);
        }

        @Override
        public Void visitNewClass(NewClassTree nc, Void v) {
            Member cur = stack.peek();
            Element e = trees.getElement(getCurrentPath());
            if (cur != null && e instanceof ExecutableElement) {
                ExecutableElement ctor = (ExecutableElement) e;
                if (isProject(ctor)) cur.calls.add(idOf(ctor));
                classifyRef(cur, ctor);
                String owner = qn((TypeElement) ctor.getEnclosingElement());
                if (owner.equals("java.util.Random")) cur.nondet.add("random");
                if (owner.equals("java.util.Date") && nc.getArguments().isEmpty()) cur.nondet.add("time");
                if (owner.equals("java.lang.String") && nc.getArguments().size() == 1) cur.nondet.add("charset");
                if (owner.equals("com.hybrid.blockchain.PeerNode")) {
                    int n = nc.getArguments().size();
                    cur.wiring.add(n == 6 ? "prod-tls(CA)" : "legacy-trust-all(" + n + "-arg PeerNode)");
                }
                if (owner.equals("com.hybrid.App")) cur.wiring.add("prod-entry");
            }
            if (cur != null && nc.getClassBody() != null) {
                // anonymous class: its members are separate rows (visitClass handles them);
                // the enclosing member "creates" it, so add an edge to each of its methods.
                Element anon = trees.getElement(new TreePath(getCurrentPath(), nc.getClassBody()));
                if (anon instanceof TypeElement) {
                    for (Element en : ((TypeElement) anon).getEnclosedElements()) {
                        if (en instanceof ExecutableElement) cur.calls.add(idOf((ExecutableElement) en));
                    }
                }
            }
            return super.visitNewClass(nc, v);
        }

        @Override
        public Void visitIdentifier(IdentifierTree it, Void v) {
            Member cur = stack.peek();
            Element e = trees.getElement(getCurrentPath());
            noteConfigRead(cur, e);
            noteFieldUse(cur, e);
            return super.visitIdentifier(it, v);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree ms, Void v) {
            Member cur = stack.peek();
            Element e = trees.getElement(getCurrentPath());
            noteConfigRead(cur, e);
            noteFieldUse(cur, e);
            if (cur != null && e instanceof VariableElement && isProject(e)) classifyRef(cur, e);
            return super.visitMemberSelect(ms, v);
        }

        void noteFieldUse(Member cur, Element e) {
            if (cur == null || !(e instanceof VariableElement)) return;
            if (e.getKind() != ElementKind.FIELD && e.getKind() != ElementKind.ENUM_CONSTANT) return;
            if (!isProject(e) || !(e.getEnclosingElement() instanceof TypeElement)) return;
            cur.calls.add(bin((TypeElement) e.getEnclosingElement()) + "#field-init:" + e.getSimpleName());
        }

        void noteConfigRead(Member cur, Element e) {
            if (cur == null || !(e instanceof VariableElement) || e.getKind() != ElementKind.FIELD) return;
            Element owner = e.getEnclosingElement();
            if (owner instanceof TypeElement && qn((TypeElement) owner).equals("com.hybrid.blockchain.Config")) {
                cur.configReads.add(e.getSimpleName().toString());
            }
        }

        @Override
        public Void visitAssignment(AssignmentTree at, Void v) {
            markFieldWrite(at.getVariable());
            return super.visitAssignment(at, v);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree ct, Void v) {
            markFieldWrite(ct.getVariable());
            return super.visitCompoundAssignment(ct, v);
        }

        @Override
        public Void visitUnary(UnaryTree ut, Void v) {
            Tree.Kind k = ut.getKind();
            if (k == Tree.Kind.POSTFIX_INCREMENT || k == Tree.Kind.PREFIX_INCREMENT
                    || k == Tree.Kind.POSTFIX_DECREMENT || k == Tree.Kind.PREFIX_DECREMENT) {
                markFieldWrite(ut.getExpression());
            }
            return super.visitUnary(ut, v);
        }

        void markFieldWrite(ExpressionTree target) {
            Member cur = stack.peek();
            if (cur == null) return;
            Element e = trees.getElement(new TreePath(getCurrentPath(), target));
            if (e instanceof VariableElement && e.getKind() == ElementKind.FIELD && isProject(e)) cur.state = true;
        }

        @Override
        public Void visitSynchronized(SynchronizedTree st, Void v) {
            if (stack.peek() != null) stack.peek().sync = true;
            return super.visitSynchronized(st, v);
        }

        @Override public Void visitIf(IfTree t, Void v) { cc(); return super.visitIf(t, v); }
        @Override public Void visitForLoop(ForLoopTree t, Void v) { cc(); return super.visitForLoop(t, v); }
        @Override public Void visitWhileLoop(WhileLoopTree t, Void v) { cc(); return super.visitWhileLoop(t, v); }
        @Override public Void visitDoWhileLoop(DoWhileLoopTree t, Void v) { cc(); return super.visitDoWhileLoop(t, v); }
        @Override public Void visitCatch(CatchTree t, Void v) { cc(); return super.visitCatch(t, v); }
        @Override public Void visitConditionalExpression(ConditionalExpressionTree t, Void v) { cc(); return super.visitConditionalExpression(t, v); }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void v) {
            cc();
            Member cur = stack.peek();
            if (cur != null) {
                TypeMirror tm = trees.getTypeMirror(new TreePath(getCurrentPath(), t.getExpression()));
                if (isHashIteration(tm)) cur.nondet.add("hash-iteration");
            }
            return super.visitEnhancedForLoop(t, v);
        }

        @Override
        public Void visitCase(CaseTree t, Void v) {
            if (!t.getExpressions().isEmpty()) cc();
            return super.visitCase(t, v);
        }

        @Override
        public Void visitBinary(BinaryTree t, Void v) {
            if (t.getKind() == Tree.Kind.CONDITIONAL_AND || t.getKind() == Tree.Kind.CONDITIONAL_OR) cc();
            Member cur = stack.peek();
            if (cur != null) {
                TypeMirror tm = trees.getTypeMirror(getCurrentPath());
                if (tm != null && (tm.getKind() == TypeKind.DOUBLE || tm.getKind() == TypeKind.FLOAT)) cur.nondet.add("float");
            }
            return super.visitBinary(t, v);
        }

        void cc() {
            Member cur = stack.peek();
            if (cur != null && !cur.graphOnly) cur.cc++;
            else if (cur != null) {
                // lambda complexity is charged to the nearest real member
                for (Member m : stack) if (!m.graphOnly) { m.cc++; break; }
            }
        }

        // ── classification helpers ────────────────────────────────────────
        void classifyEntry(Member m, ExecutableElement ee, TypeElement owner, MethodTree mt) {
            if ("test".equals(scope)) {
                for (AnnotationTree a : mt.getModifiers().getAnnotations()) {
                    String an = a.getAnnotationType().toString();
                    if (an.endsWith("Test") || an.endsWith("ParameterizedTest") || an.endsWith("RepeatedTest")
                            || an.endsWith("TestFactory") || an.endsWith("Property") || an.endsWith("FuzzTest")) {
                        m.entry.add("test");
                    }
                }
                return;
            }
            String name = ee.getSimpleName().toString();
            if (name.equals("main") && ee.getModifiers().contains(Modifier.STATIC)
                    && ee.getModifiers().contains(Modifier.PUBLIC)) m.entry.add("main");
            for (AnnotationTree a : mt.getModifiers().getAnnotations()) {
                String an = a.getAnnotationType().toString();
                String simple = an.substring(an.lastIndexOf('.') + 1);
                switch (simple) {
                    case "GetMapping": case "PostMapping": case "PutMapping": case "DeleteMapping":
                    case "PatchMapping": case "RequestMapping":
                        m.entry.add("rest:" + simple.replace("Mapping", "").toUpperCase(Locale.ROOT)
                                + " " + annotationPath(a));
                        break;
                    case "Bean": m.entry.add("spring-bean"); break;
                    case "Scheduled": m.entry.add("scheduled"); break;
                    case "PostConstruct": m.entry.add("spring-bean"); break;
                    case "JsonCreator": m.entry.add("jackson"); break;
                    default: break;
                }
            }
            for (VariableElement prm : ee.getParameters()) {
                for (AnnotationMirror am : prm.getAnnotationMirrors()) {
                    String t = am.getAnnotationType().toString();
                    if (t.endsWith(".RequestBody") || t.endsWith(".ModelAttribute")) {
                        Element pt = types.asElement(prm.asType());
                        if (pt instanceof TypeElement && isProject(pt)) jacksonTargets.add(bin((TypeElement) pt));
                    }
                }
            }
            if (ee.getKind() == ElementKind.CONSTRUCTOR) {
                for (AnnotationMirror am : owner.getAnnotationMirrors()) {
                    String t = am.getAnnotationType().toString();
                    if (t.endsWith(".RestController") || t.endsWith(".Controller") || t.endsWith(".Component")
                            || t.endsWith(".Configuration") || t.endsWith(".Service") || t.endsWith(".SpringBootApplication")
                            || t.endsWith(".EnableWebSocket")) m.entry.add("spring-bean");
                }
            }
            if (subtypeOf(owner, "org.eclipse.californium.core.CoapResource") && name.startsWith("handle")) m.entry.add("coap");
            if (subtypeOf(owner, "org.eclipse.paho.client.mqttv3.MqttCallback")
                    && Set.of("messageArrived", "connectionLost", "deliveryComplete").contains(name)) m.entry.add("mqtt");
            if (subtypeOf(owner, "org.springframework.web.socket.WebSocketHandler")
                    && (name.startsWith("handle") || name.startsWith("afterConnection"))) m.entry.add("websocket");
            if (subtypeOf(owner, "org.springframework.web.filter.OncePerRequestFilter") && name.equals("doFilterInternal")) m.entry.add("filter");
            if (subtypeOf(owner, "org.springframework.web.servlet.HandlerInterceptor")
                    && Set.of("preHandle", "postHandle", "afterCompletion").contains(name)) m.entry.add("interceptor");
        }

        void recordOverrides(ExecutableElement ee, TypeElement owner, Member m) {
            if (ee.getKind() != ElementKind.METHOD) return;
            for (TypeMirror st : allSupertypes(owner.asType())) {
                Element se = types.asElement(st);
                if (!(se instanceof TypeElement)) continue;
                for (Element en : se.getEnclosedElements()) {
                    if (en instanceof ExecutableElement && en.getSimpleName().equals(ee.getSimpleName())
                            && elements.overrides(ee, (ExecutableElement) en, owner)) {
                        if (isProject(en)) {
                            overrideEdges.computeIfAbsent(idOf((ExecutableElement) en), k -> new TreeSet<>()).add(m.id);
                        } else {
                            m.entry.add("framework-override");
                        }
                    }
                }
            }
        }

        String registrationKind(TreePath lambdaPath) {
            TreePath p = lambdaPath.getParentPath();
            while (p != null && !(p.getLeaf() instanceof MethodInvocationTree) && !(p.getLeaf() instanceof NewClassTree)) {
                if (p.getLeaf() instanceof StatementTree) return null;
                p = p.getParentPath();
            }
            if (p == null) return null;
            if (p.getLeaf() instanceof NewClassTree) {
                Element e = trees.getElement(p);
                if (e != null && e.getEnclosingElement().toString().equals("java.lang.Thread")) return "async";
                return null;
            }
            MethodInvocationTree mi = (MethodInvocationTree) p.getLeaf();
            String n = methodName(mi);
            Element e = trees.getElement(p);
            String owner = e != null && e.getEnclosingElement() instanceof TypeElement
                    ? qn((TypeElement) e.getEnclosingElement()) : "";
            if (n.equals("registerHandler") && owner.endsWith("GossipEngine")) return "p2p-handler";
            if (n.equals("setRelayDispatcher")) return "p2p-handler";
            if (n.startsWith("schedule")) return "scheduled";
            if (Set.of("submit", "execute", "runAsync", "supplyAsync").contains(n)) return "async";
            if (n.equals("subscribe") || n.equals("setCallback") || n.equals("addShutdownHook")) return "async";
            return null;
        }

        void classifyRef(Member cur, Element e) {
            if (cur == null || e == null) return;
            Element o = e instanceof TypeElement ? e : e.getEnclosingElement();
            if (!(o instanceof TypeElement)) return;
            String q = qn((TypeElement) o);
            if (q.startsWith("java.security.") || q.startsWith("javax.crypto.") || q.startsWith("javax.net.ssl.")
                    || q.startsWith("org.bouncycastle.") || q.startsWith("io.jsonwebtoken.")
                    || q.equals("com.hybrid.blockchain.Crypto") || q.endsWith(".QuantumResistantCrypto")
                    || q.startsWith("com.hybrid.blockchain.privacy.ZKProofSystem") || q.endsWith(".CertificateAuthority")
                    || q.endsWith(".SSLUtils") || q.endsWith(".PUFIdentityProvider") || q.endsWith(".JwtManager")) cur.crypto = true;
            if (q.startsWith("java.io.") || q.startsWith("java.nio.file.") || q.startsWith("java.nio.channels.")
                    || q.startsWith("java.net.") || q.startsWith("javax.net.") || q.startsWith("org.iq80.leveldb")
                    || q.startsWith("org.fusesource.leveldbjni") || q.startsWith("org.eclipse.paho")
                    || q.startsWith("org.eclipse.californium") || q.startsWith("java.sql.")
                    || q.equals("com.hybrid.blockchain.Storage")) cur.io = true;
        }

        static final Set<String> MUTATORS = Set.of("put", "putAll", "putIfAbsent", "remove", "removeIf", "add",
                "addAll", "clear", "compute", "computeIfAbsent", "computeIfPresent", "merge", "offer", "poll",
                "set", "replace", "replaceAll", "push", "pop", "addFirst", "addLast", "removeFirst", "removeLast",
                "retainAll", "incrementAndGet", "getAndIncrement", "addAndGet", "decrementAndGet", "getAndSet", "lazySet");
        static final Set<String> STATE_OWNERS = Set.of("AccountState", "UTXOSet", "Mempool", "TokenRegistry", "Storage",
                "Blockchain", "DeviceLifecycleManager", "SSIManager");
        static final Pattern MUTATOR_NAME = Pattern.compile(
                "^(set|put|add|remove|credit|debit|increment|apply|merge|save|delete|clear|spend|mint|burn|transfer|register|revoke|update|slash|mark|commit|revert|restore|index|write|ensure)[A-Z]?.*");

        void classifyCall(Member cur, ExecutableElement ee, MethodInvocationTree mi, String name) {
            Element ownerEl = ee.getEnclosingElement();
            String owner = ownerEl instanceof TypeElement ? qn((TypeElement) ownerEl) : "";
            String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
            // state: mutator on a field receiver
            if (mi.getMethodSelect() instanceof MemberSelectTree && MUTATORS.contains(name)) {
                ExpressionTree recv = ((MemberSelectTree) mi.getMethodSelect()).getExpression();
                Element re = trees.getElement(new TreePath(getCurrentPath(), recv));
                if (re instanceof VariableElement && re.getKind() == ElementKind.FIELD) cur.state = true;
            }
            if (owner.startsWith("com.hybrid") && STATE_OWNERS.contains(simpleOwner) && MUTATOR_NAME.matcher(name).matches()) cur.state = true;
            // network ingress: reading framed bytes from a peer stream
            if (owner.equals("java.io.DataInputStream") && name.startsWith("read") && "main".equals(scope)) cur.entry.add("p2p-ingress");
            // locks
            if (Set.of("lock", "unlock", "tryLock", "lockInterruptibly").contains(name)
                    && subtypeOf(ownerEl, "java.util.concurrent.locks.Lock")) cur.sync = true;
            // nondeterminism
            if (owner.equals("java.lang.System") && (name.equals("currentTimeMillis") || name.equals("nanoTime"))) cur.nondet.add("time");
            if ((owner.equals("java.time.Instant") || owner.equals("java.time.LocalDateTime")) && name.equals("now")) cur.nondet.add("time");
            if (owner.equals("java.lang.Math") && name.equals("random")) cur.nondet.add("random");
            if (owner.equals("java.util.concurrent.ThreadLocalRandom")) cur.nondet.add("random");
            if (owner.equals("java.util.Random") || owner.equals("java.util.Collections") && name.equals("shuffle")) cur.nondet.add("random");
            if (owner.equals("java.lang.String") && name.equals("getBytes") && mi.getArguments().isEmpty()) cur.nondet.add("charset");
            if (owner.equals("java.lang.String") && (name.equals("toLowerCase") || name.equals("toUpperCase")) && mi.getArguments().isEmpty()) cur.nondet.add("locale");
            if (owner.equals("java.lang.String") && name.equals("format") && !mi.getArguments().isEmpty()) {
                TypeMirror first = trees.getTypeMirror(new TreePath(getCurrentPath(), mi.getArguments().get(0)));
                if (first == null || !first.toString().equals("java.util.Locale")) cur.nondet.add("locale");
            }
            if (name.equals("parallelStream") || (name.equals("parallel") && owner.startsWith("java.util.stream"))) cur.nondet.add("parallel");
            if (Set.of("entrySet", "keySet", "values", "forEach", "stream").contains(name)
                    && mi.getMethodSelect() instanceof MemberSelectTree) {
                TypeMirror rt = trees.getTypeMirror(new TreePath(getCurrentPath(),
                        ((MemberSelectTree) mi.getMethodSelect()).getExpression()));
                if (isHashIteration(rt)) cur.nondet.add("hash-iteration");
            }
            // test-side metrics
            if ("test".equals(scope)) {
                if (owner.equals("org.mockito.Mockito") && (name.equals("mock") || name.equals("spy")) && !mi.getArguments().isEmpty()) {
                    TypeMirror t = trees.getTypeMirror(new TreePath(getCurrentPath(), mi.getArguments().get(0)));
                    cur.mocks.add(mockedName(t, mi.getArguments().get(0)));
                }
                if (owner.equals("java.lang.System") && name.equals("setProperty") && !mi.getArguments().isEmpty()) {
                    cur.sysProps.add(literal(mi.getArguments().get(0)));
                }
                if (owner.equals("java.lang.Thread") && name.equals("sleep")) cur.sleeps = true;
                if (owner.startsWith("org.awaitility")) cur.sleeps = true;
                if (owner.endsWith("SSLUtils")) {
                    cur.wiring.add(name.equals("createSSLContextWithCA") ? "prod-tls(CA)" : "legacy-trust-all(SSLUtils." + name + ")");
                }
                if (owner.startsWith("org.springframework.test.web.servlet")) cur.wiring.add("spring-mockmvc");
            }
        }

        void countAssertion(Member cur, String name, MethodInvocationTree mi) {
            if (!"test".equals(scope)) return;
            Set<String> kinds = Set.of("assertThat", "assertEquals", "assertNotEquals", "assertTrue", "assertFalse",
                    "assertNotNull", "assertNull", "assertThrows", "assertDoesNotThrow", "assertThatThrownBy",
                    "assertThatCode", "assertArrayEquals", "assertSame", "assertNotSame", "assertAll", "assertTimeout",
                    "assertTimeoutPreemptively", "assertThatExceptionOfType", "assertThatNoException", "fail", "verify",
                    "verifyNoInteractions", "verifyNoMoreInteractions", "assertIterableEquals", "assertLinesMatch");
            if (kinds.contains(name)) {
                Member real = cur;
                for (Member m : stack) if (!m.graphOnly) { real = m; break; }
                real.assertions++;
                real.assertKinds.add(name);
            }
        }

        void recordEnv(Member cur, MethodInvocationTree mi, String name) {
            if (mi.getArguments().isEmpty()) return;
            Element e = trees.getElement(getCurrentPath());
            if (e == null) return;
            String owner = e.getEnclosingElement().toString();
            boolean envish = (owner.equals("java.lang.System") && (name.equals("getenv") || name.equals("getProperty")))
                    || (owner.equals("com.hybrid.blockchain.Config") && name.matches("get(Int|Boolean)?Env"))
                    || (owner.equals("java.lang.Integer") && name.equals("getInteger"))
                    || (owner.equals("java.lang.Boolean") && name.equals("getBoolean"))
                    || (owner.equals("java.lang.Long") && name.equals("getLong"));
            if (!envish) return;
            String key = literal(mi.getArguments().get(0));
            String def = mi.getArguments().size() > 1 ? mi.getArguments().get(1).toString() : "";
            envReads.add(new String[]{key, owner.endsWith("System") ? "System." + name : owner + "." + name,
                    def, realMember(cur).id, file + ":" + line(mi, false)});
        }

        Member realMember(Member cur) {
            for (Member m : stack) if (!m.graphOnly) return m;
            return cur;
        }

        void recordJacksonTarget(MethodInvocationTree mi, String name) {
            if (Set.of("writeValueAsBytes", "writeValueAsString", "writeValue", "valueToTree", "ok", "body", "publish", "convertAndSend").contains(name)) {
                for (ExpressionTree a : mi.getArguments()) {
                    TypeMirror tm = trees.getTypeMirror(new TreePath(getCurrentPath(), a));
                    if (tm != null && tm.getKind() == TypeKind.DECLARED) {
                        Element te = types.asElement(tm);
                        if (te instanceof TypeElement && isProject(te)) jacksonSerialized.add(bin((TypeElement) te));
                    }
                }
            }
            if (!Set.of("readValue", "convertValue", "treeToValue").contains(name)) return;
            for (ExpressionTree a : mi.getArguments()) {
                if (a instanceof MemberSelectTree && ((MemberSelectTree) a).getIdentifier().contentEquals("class")) {
                    Element e = trees.getElement(new TreePath(getCurrentPath(), ((MemberSelectTree) a).getExpression()));
                    if (e instanceof TypeElement && isProject(e)) jacksonTargets.add(bin((TypeElement) e));
                }
            }
        }

        void recordConfigField(TypeElement te, VariableTree vt) {
            Set<Modifier> mods = vt.getModifiers().getFlags();
            if (!mods.contains(Modifier.STATIC)) return;
            String init = vt.getInitializer() == null ? "(static block)" : vt.getInitializer().toString();
            String key = firstLiteralArg(vt.getInitializer());
            configFields.put(vt.getName().toString(), new String[]{vt.getType().toString(),
                    String.valueOf(mods.contains(Modifier.FINAL)), init.length() > 90 ? init.substring(0, 90) + "…" : init, key,
                    file + ":" + line(vt, false)});
        }
    }

    // ─── shared helpers ───────────────────────────────────────────────────────
    static final class Pattern {
        final java.util.regex.Pattern p;
        Pattern(String r) { p = java.util.regex.Pattern.compile(r); }
        static Pattern compile(String r) { return new Pattern(r); }
        java.util.regex.Matcher matcher(String s) { return p.matcher(s); }
    }

    static String methodName(MethodInvocationTree mi) {
        ExpressionTree s = mi.getMethodSelect();
        if (s instanceof MemberSelectTree) return ((MemberSelectTree) s).getIdentifier().toString();
        if (s instanceof IdentifierTree) return ((IdentifierTree) s).getName().toString();
        return s.toString();
    }

    static String annotationPath(AnnotationTree a) {
        for (ExpressionTree arg : a.getArguments()) {
            String s = arg.toString();
            int q = s.indexOf('"');
            if (q >= 0) return s.substring(q, s.lastIndexOf('"') + 1);
        }
        return "\"\"";
    }

    /** First string literal reachable through nested invocation arguments, e.g. parse(getEnv("K", d)) -> K. */
    static String firstLiteralArg(ExpressionTree t) {
        if (!(t instanceof MethodInvocationTree)) return "";
        for (ExpressionTree a : ((MethodInvocationTree) t).getArguments()) {
            if (a instanceof LiteralTree && ((LiteralTree) a).getValue() instanceof String) return (String) ((LiteralTree) a).getValue();
            String inner = firstLiteralArg(a);
            if (!inner.isEmpty()) return inner;
        }
        return "";
    }

    static String literal(ExpressionTree t) {
        if (t instanceof LiteralTree) return String.valueOf(((LiteralTree) t).getValue());
        return "<expr:" + t + ">";
    }

    static String mockedName(TypeMirror t, ExpressionTree arg) {
        String s = arg.toString();
        return s.endsWith(".class") ? s.substring(0, s.length() - 6) : (t == null ? s : t.toString());
    }

    static boolean isConstant(ExpressionTree t) {
        if (t instanceof LiteralTree) return true;
        if (t instanceof UnaryTree) return isConstant(((UnaryTree) t).getExpression());
        if (t instanceof BinaryTree) return isConstant(((BinaryTree) t).getLeftOperand()) && isConstant(((BinaryTree) t).getRightOperand());
        if (t instanceof ParenthesizedTree) return isConstant(((ParenthesizedTree) t).getExpression());
        if (t instanceof IdentifierTree) return true;      // reference to another constant
        if (t instanceof MemberSelectTree) return true;    // e.g. Foo.BAR, Long.MAX_VALUE
        return false;
    }

    static boolean isEnumConstant(TypeElement te, VariableTree vt) {
        for (Element e : te.getEnclosedElements())
            if (e.getKind() == ElementKind.ENUM_CONSTANT && e.getSimpleName().contentEquals(vt.getName())) return true;
        return false;
    }

    static boolean hasFunctional(ExpressionTree t) {
        final boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void v) { found[0] = true; return null; }
            @Override public Void visitMemberReference(MemberReferenceTree m, Void v) { found[0] = true; return null; }
            @Override public Void visitNewClass(NewClassTree n, Void v) {
                if (n.getClassBody() != null) found[0] = true;
                return super.visitNewClass(n, v);
            }
        }.scan(t, null);
        return found[0];
    }

    static boolean isHashIteration(TypeMirror tm) {
        if (tm == null || tm.getKind() != TypeKind.DECLARED) return false;
        String raw = types.erasure(tm).toString();
        return raw.equals("java.util.HashMap") || raw.equals("java.util.HashSet")
                || raw.equals("java.util.concurrent.ConcurrentHashMap")
                || raw.startsWith("java.util.HashMap.") || raw.startsWith("java.util.concurrent.ConcurrentHashMap.")
                || raw.equals("java.util.Map") || raw.equals("java.util.Set")
                || raw.equals("java.util.Collection");   // static type too weak to prove order: flagged for review
    }

    static List<TypeMirror> allSupertypes(TypeMirror t) {
        List<TypeMirror> out = new ArrayList<>();
        Deque<TypeMirror> q = new ArrayDeque<>(types.directSupertypes(t));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            TypeMirror s = q.poll();
            if (!seen.add(types.erasure(s).toString())) continue;
            out.add(s);
            q.addAll(types.directSupertypes(s));
        }
        return out;
    }

    static boolean subtypeOf(Element owner, String fqn) {
        if (!(owner instanceof TypeElement)) return false;
        TypeElement target = elements.getTypeElement(fqn);
        if (target == null) return false;
        return types.isSubtype(types.erasure(owner.asType()), types.erasure(target.asType()));
    }

    static boolean isProject(Element e) {
        Element o = e;
        while (o != null && !(o instanceof PackageElement)) o = o.getEnclosingElement();
        return o != null && o.toString().startsWith("com.hybrid");
    }

    static String bin(TypeElement te) { return elements.getBinaryName(te).toString(); }
    static String qn(TypeElement te) { return te.getQualifiedName().toString(); }

    static String idOf(ExecutableElement ee) {
        Element o = ee.getEnclosingElement();
        String owner = o instanceof TypeElement ? bin((TypeElement) o) : o.toString();
        String nm = ee.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : ee.getSimpleName().toString();
        String params = ee.getParameters().stream().map(p -> types.erasure(p.asType()).toString())
                .collect(Collectors.joining(","));
        return owner + "#" + nm + "(" + params + ")";
    }

    static String signature(ExecutableElement ee) {
        String params = ee.getParameters().stream().map(p -> types.erasure(p.asType()) + " " + p.getSimpleName())
                .collect(Collectors.joining(", "));
        String ret = ee.getKind() == ElementKind.CONSTRUCTOR ? "" : types.erasure(ee.getReturnType()) + " ";
        return ret + (ee.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : ee.getSimpleName()) + "(" + params + ")";
    }

    static String visibility(ModifiersTree m) {
        Set<Modifier> f = m.getFlags();
        if (f.contains(Modifier.PUBLIC)) return "public";
        if (f.contains(Modifier.PROTECTED)) return "protected";
        if (f.contains(Modifier.PRIVATE)) return "private";
        return "package";
    }

    static String hash(String s) {
        try {
            String norm = s.replaceAll("\\s+", " ").replaceAll("\"[^\"]*\"", "\"\"").replaceAll("\\b\\d+\\b", "0");
            byte[] d = MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    // ─── outputs ──────────────────────────────────────────────────────────────
    static String csv(Object... cols) {
        return Arrays.stream(cols).map(c -> {
            String s = c == null ? "" : String.valueOf(c);
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                s = "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
            }
            return s;
        }).collect(Collectors.joining(",")) + "\n";
    }

    static void writeFiles(Path proj, Path inv, List<Path> main, List<Path> test) throws IOException {
        StringBuilder sb = new StringBuilder(csv("path", "scope", "package", "loc_total", "loc_nonblank"));
        for (List<Path> group : List.of(main, test)) {
            for (Path p : group) {
                List<String> ls = Files.readAllLines(p, StandardCharsets.UTF_8);
                String pkg = ls.stream().filter(l -> l.startsWith("package ")).findFirst()
                        .map(l -> l.substring(8).replace(";", "").trim()).orElse("");
                long nb = ls.stream().filter(l -> !l.isBlank()).count();
                String rel = proj.relativize(p).toString().replace('\\', '/');
                sb.append(csv(rel, rel.startsWith("src/test") ? "test" : "main", pkg, ls.size(), nb));
            }
        }
        Files.writeString(inv.resolve("files.csv"), sb.toString());
    }

    static void writeOutputs(Path inv) throws IOException {
        // apply override edges: a call to an interface/super method reaches each override
        for (Member m : members.values()) {
            Set<String> extra = new LinkedHashSet<>();
            for (String c : m.calls) {
                Set<String> o = overrideEdges.get(c);
                if (o != null) extra.addAll(o);
            }
            m.calls.addAll(extra);
        }
        // implicit JVM edges: constructors run instance field-inits/init-blocks; any member of a
        // class triggers its static field-inits/static blocks (class initialisation).
        Map<String, List<Member>> byOwner = new HashMap<>();
        for (Member m : members.values()) if (m.id != null && !m.graphOnly) byOwner.computeIfAbsent(m.owner, k -> new ArrayList<>()).add(m);
        for (List<Member> cls : byOwner.values()) {
            List<String> staticInits = new ArrayList<>(), instInits = new ArrayList<>();
            for (Member m : cls) {
                boolean isStaticField = m.kind.startsWith("field-init") && m.signature != null && isStaticField(m);
                if (m.kind.equals("static-init") || isStaticField) staticInits.add(m.id);
                else if (m.kind.equals("instance-init") || m.kind.startsWith("field-init")) instInits.add(m.id);
            }
            for (Member m : cls) {
                if (m.kind.equals("constructor")) m.calls.addAll(instInits);
                if (!staticInits.contains(m.id)) m.calls.addAll(staticInits);
            }
        }
        Map<String, Set<String>> inboundMain = new HashMap<>();
        Map<String, Set<String>> inboundTest = new HashMap<>();
        for (Member m : members.values()) {
            for (String c : m.calls) {
                if (c.equals(m.id)) continue;
                ("test".equals(m.scope) ? inboundTest : inboundMain).computeIfAbsent(c, k -> new TreeSet<>()).add(m.id);
            }
        }
        // propagate lambda inbound: a lambda is "called" by its enclosing member (edge already added)

        // reachability from untrusted entries and from tests
        Set<String> untrustedRoots = members.values().stream()
                .filter(m -> "main".equals(m.scope) && m.entry.stream().anyMatch(e -> UNTRUSTED.contains(e.split(":")[0])))
                .map(m -> m.id).collect(Collectors.toCollection(TreeSet::new));
        Map<String, String> untrustedVia = bfs(untrustedRoots, true);
        Set<String> testRoots = members.values().stream().filter(m -> m.entry.contains("test")).map(m -> m.id)
                .collect(Collectors.toSet());
        Set<String> entryRoots = members.values().stream()
                .filter(m -> "main".equals(m.scope) && m.id != null && !m.entry.isEmpty() && !m.entry.contains("test"))
                .map(m -> m.id).collect(Collectors.toSet());
        Set<String> liveFromEntries = bfs(entryRoots, true).keySet();
        Map<String, Integer> testReach = new HashMap<>();
        for (String t : testRoots) {
            for (String r : bfs(Set.of(t), false).keySet()) testReach.merge(r, 1, Integer::sum);
        }

        StringBuilder m1 = new StringBuilder(csv("id", "class", "member", "kind", "signature", "visibility", "file",
                "line", "loc", "cc", "is_public_entry", "untrusted_reachable", "untrusted_via", "inbound_main",
                "inbound_test", "tests_reaching", "dead", "test_only", "touches_crypto", "touches_io",
                "touches_state", "sync_or_lock", "nondeterminism", "config_reads"));
        List<Member> mainMembers = members.values().stream().filter(m -> "main".equals(m.scope) && !m.graphOnly && m.id != null)
                .sorted(Comparator.comparing((Member m) -> m.owner).thenComparingInt(m -> m.startLine))
                .collect(Collectors.toList());
        int n = 0;
        for (Member m : mainMembers) {
            // lambdas inherit classification into the enclosing member
            for (Member lam : members.values()) {
                if (lam.graphOnly && lam.id != null && lam.id.startsWith(m.id + "$lambda@")) {
                    m.crypto |= lam.crypto; m.io |= lam.io; m.state |= lam.state; m.sync |= lam.sync;
                    m.nondet.addAll(lam.nondet); m.configReads.addAll(lam.configReads);
                    for (String e : lam.entry) m.entry.add(e + "(lambda)");
                }
            }
            int inM = inboundMain.getOrDefault(m.id, Set.of()).size();
            int inT = inboundTest.getOrDefault(m.id, Set.of()).size();
            boolean entry = !m.entry.isEmpty();
            boolean fw = m.entry.contains("framework-override");
            boolean live = liveFromEntries.contains(m.id);
            boolean dead = !live;
            boolean testOnly = !live && testReach.containsKey(m.id);
            String via = untrustedVia.get(m.id);
            m1.append(csv(String.format(Locale.ROOT, "M-%04d", ++n), m.owner, m.name, m.kind, m.signature, m.visibility,
                    m.file, m.startLine, m.endLine - m.startLine + 1, m.cc, String.join(";", m.entry),
                    via != null, via == null ? "" : via, inM, inT, testReach.getOrDefault(m.id, 0),
                    dead && !testOnly, testOnly, m.crypto, m.io, m.state, m.sync, String.join(";", m.nondet),
                    String.join(";", m.configReads)));
        }
        Files.writeString(inv.resolve("methods.csv"), m1.toString());

        // tests
        StringBuilder t1 = new StringBuilder(csv("id", "class", "method", "file", "line", "tags", "disabled",
                "assertions", "assert_kinds", "only_no_exception", "mocks", "mocks_cut", "local_sysprops",
                "global_overrides", "wiring", "sleep_or_await", "dup_group", "main_members_reached"));
        Map<String, String> classTags = new HashMap<>();
        Map<String, Boolean> classDisabled = new HashMap<>();
        Map<String, Set<String>> classMocks = new HashMap<>();
        Map<String, Set<String>> classProps = new HashMap<>();
        Map<String, Set<String>> classWiring = new HashMap<>();
        for (Member m : members.values()) {
            if (!"test".equals(m.scope) || m.id == null) continue;
            classMocks.computeIfAbsent(m.owner, k -> new TreeSet<>()).addAll(m.mocks);
            classProps.computeIfAbsent(m.owner, k -> new TreeSet<>()).addAll(m.sysProps);
            classWiring.computeIfAbsent(m.owner, k -> new TreeSet<>()).addAll(m.wiring);
        }
        Map<String, List<String>> dupGroups = new HashMap<>();
        List<Member> tests = members.values().stream().filter(m -> m.entry.contains("test"))
                .sorted(Comparator.comparing((Member m) -> m.owner).thenComparingInt(m -> m.startLine))
                .collect(Collectors.toList());
        for (Member t : tests) dupGroups.computeIfAbsent(t.bodyHash, k -> new ArrayList<>()).add(t.id);
        String global = "-DDEBUG=true -DREQUIRE_QUANTUM_SIG=false -DMIN_PEERS=1 -DSTORAGE_AES_KEY=<fixed> NODE_PRIVATE_KEY=<fixed>";
        int k = 0;
        for (Member t : tests) {
            TypeElement te = elements.getTypeElement(t.owner.replace('$', '.'));
            String tags = "", disabled = "";
            if (te != null) {
                tags = annos(te, "Tag");
                disabled = annos(te, "Disabled");
            }
            for (String a : t.annotations) {
                if (a.contains("@Tag(")) tags += (tags.isEmpty() ? "" : ";") + a;
                if (a.contains("@Disabled") || a.contains("@EnabledIf") || a.contains("@DisabledIf")
                        || a.contains("@EnabledOn") || a.contains("@DisabledOn")) disabled += (disabled.isEmpty() ? "" : ";") + a;
            }
            Set<String> mocks = new TreeSet<>(classMocks.getOrDefault(t.owner, Set.of()));
            mocks.addAll(FIELD_MOCKS.getOrDefault(t.owner, Set.of()));
            String cut = t.owner.substring(t.owner.lastIndexOf('.') + 1).replaceAll("\\$.*", "")
                    .replaceAll("(Complete|E2E|Integration|Unit|Security|Adversarial)?Tests?$", "");
            boolean mocksCut = mocks.stream().anyMatch(x -> x.endsWith(cut) || x.endsWith("." + cut));
            Set<String> props = new TreeSet<>(classProps.getOrDefault(t.owner, Set.of()));
            Set<String> wiring = new TreeSet<>(classWiring.getOrDefault(t.owner, Set.of()));
            if (wiring.isEmpty()) wiring.add("unit");
            boolean onlyNoEx = t.assertions > 0 && t.assertKinds.stream()
                    .allMatch(x -> x.equals("assertDoesNotThrow") || x.equals("assertThatCode") || x.equals("assertThatNoException"));
            List<String> grp = dupGroups.get(t.bodyHash);
            Set<String> reached = bfs(Set.of(t.id), false).keySet().stream()
                    .filter(id -> members.containsKey(id) && "main".equals(members.get(id).scope) && !members.get(id).graphOnly)
                    .collect(Collectors.toCollection(TreeSet::new));
            t1.append(csv(String.format(Locale.ROOT, "T-%04d", ++k), t.owner, t.name, t.file, t.startLine, tags, disabled,
                    t.assertions, String.join(";", t.assertKinds), onlyNoEx, String.join(";", mocks), mocksCut,
                    String.join(";", props), global, String.join(";", wiring), t.sleeps,
                    grp != null && grp.size() > 1 ? t.bodyHash : "", reached.size()));
        }
        Files.writeString(inv.resolve("tests.csv"), t1.toString());

        // config
        Set<String> consensusRoots = members.values().stream().filter(m -> m.id != null && "main".equals(m.scope)
                && (m.id.startsWith("com.hybrid.blockchain.Blockchain#validateTransaction(")
                || m.id.startsWith("com.hybrid.blockchain.Blockchain#applyBlock(")
                || m.id.startsWith("com.hybrid.blockchain.Blockchain#validateBlock(")
                || m.id.startsWith("com.hybrid.blockchain.Blockchain#applyTransactionToState(")
                || m.id.startsWith("com.hybrid.blockchain.Transaction#verify(")
                || m.id.startsWith("com.hybrid.blockchain.consensus.PBFTConsensus#validateBlock(")
                || m.id.startsWith("com.hybrid.blockchain.consensus.PBFTConsensus#verifyBlock(")
                || m.id.startsWith("com.hybrid.blockchain.consensus.PBFTConsensus#selectLeader(")
                || m.id.startsWith("com.hybrid.blockchain.consensus.PBFTConsensus#getQuorumHash(")))
                .map(m -> m.id).collect(Collectors.toSet());
        Set<String> consensusReach = bfs(consensusRoots, true).keySet();
        StringBuilder c1 = new StringBuilder(csv("name", "type", "final", "default_expr", "env_or_property_key",
                "declared_at", "readers", "consensus_critical", "consensus_readers"));
        for (Map.Entry<String, String[]> e : configFields.entrySet()) {
            String name = e.getKey();
            List<String> readers = members.values().stream().filter(m -> m.id != null && !m.graphOnly && m.configReads.contains(name))
                    .map(m -> m.id).sorted().collect(Collectors.toList());
            List<String> cr = members.values().stream().filter(m -> m.id != null && m.configReads.contains(name)
                    && consensusReach.contains(m.id)).map(m -> m.id).sorted().collect(Collectors.toList());
            String[] f = e.getValue();
            c1.append(csv(name, f[0], f[1], f[2], f[3], f[4], String.join(" | ", readers), !cr.isEmpty(), String.join(" | ", cr)));
        }
        Files.writeString(inv.resolve("config.csv"), c1.toString());

        StringBuilder e1 = new StringBuilder(csv("key", "accessor", "default", "member", "location", "consensus_critical"));
        envReads.stream().sorted(Comparator.comparing(a -> a[0])).forEach(a ->
                e1.append(csv(a[0], a[1], a[2], a[3], a[4], consensusReach.contains(a[3]))));
        Files.writeString(inv.resolve("env.csv"), e1.toString());

        StringBuilder g = new StringBuilder(csv("caller", "callee", "caller_scope"));
        for (Member m : members.values()) {
            if (m.id == null) continue;
            for (String c : m.calls) g.append(csv(m.id, c, m.scope));
        }
        Files.writeString(inv.resolve("callgraph.csv"), g.toString());
    }

    static final Set<String> STATIC_FIELDS = new HashSet<>();
    static boolean isStaticField(Member m) { return STATIC_FIELDS.contains(m.id); }

    static String annos(TypeElement te, String simple) {
        return te.getAnnotationMirrors().stream().filter(a -> a.getAnnotationType().toString().endsWith("." + simple))
                .map(Object::toString).collect(Collectors.joining(";"));
    }

    /** BFS over call edges. If mainOnly, does not traverse into test members. Returns node -> root it was reached from. */
    static Map<String, String> bfs(Set<String> roots, boolean mainOnly) {
        Map<String, String> via = new LinkedHashMap<>();
        Deque<String> q = new ArrayDeque<>();
        for (String r : roots) { via.put(r, r); q.add(r); }
        while (!q.isEmpty()) {
            String cur = q.poll();
            Member m = members.get(cur);
            if (m == null) continue;
            for (String c : m.calls) {
                Member cm = members.get(c);
                if (cm == null || via.containsKey(c)) continue;
                if (mainOnly && "test".equals(cm.scope)) continue;
                via.put(c, via.get(cur));
                q.add(c);
            }
        }
        return via;
    }
}
