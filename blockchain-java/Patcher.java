import java.nio.file.*;
import java.util.regex.*;
import java.io.*;

public class Patcher {
    public static void main(String[] args) throws Exception {
        Path p = Paths.get("src/main/java/com/hybrid/blockchain/Blockchain.java");
        String content = new String(Files.readAllBytes(p), "UTF-8");

        // 1. Fix addTransaction
        content = content.replace("throw new Exception(\"Invalid nonce: \" + tx.getNonce() + \" <= current ledger nonce \" + currentNonce);",
                                  "throw new IllegalArgumentException(\"Invalid nonce: \" + tx.getNonce() + \" <= current ledger nonce \" + currentNonce);");

        // 2. validateTransaction signature
        content = content.replace("public void validateTransaction(Transaction tx) throws Exception {",
                                  "public void validateTransaction(Transaction tx) throws Exception { validateTransaction(tx, false); }\n    public void validateTransaction(Transaction tx, boolean skipNonce) throws Exception {");

        // 3. Fix nonces in validateTransaction
        Matcher m = Pattern.compile("\\s*long (\\w+) = state\\.getNonce\\(tx\\.getFrom\\(\\)\\) \\+ 1;\\s*if \\(tx\\.getNonce\\(\\) != \\1\\)\\s*throw new.*?;").matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String var = m.group(1);
            m.appendReplacement(sb, "\n                    if (!skipNonce) {\n                        long " + var + " = state.getNonce(tx.getFrom()) + 1;\n                        if (tx.getNonce() != " + var + ") throw new IllegalArgumentException(\"Invalid nonce: expected \" + " + var + " + \" got \" + tx.getNonce());\n                    }");
        }
        m.appendTail(sb);
        content = sb.toString();

        // 4. applyBlockInternal 2 pass sort
        String applyOld = "        for (Transaction tx : block.getTransactions()) {\n            validateTransaction(tx);\n        }";
        String applyNew = "        java.util.List<Transaction> sortedTxs = new java.util.ArrayList<>(block.getTransactions());\n" +
                          "        sortedTxs.sort(java.util.Comparator\n" +
                          "                .comparingInt((Transaction tx) -> tx.getType() == Transaction.Type.MINT ? 0 : 1)\n" +
                          "                .thenComparing(tx -> tx.getFrom() == null ? \"\" : tx.getFrom())\n" +
                          "                .thenComparingLong(Transaction::getNonce));\n" +
                          "\n" +
                          "        java.util.Map<String, Long> expectedNonces = new java.util.LinkedHashMap<>();\n" +
                          "        for (Transaction tx : sortedTxs) {\n" +
                          "            if (tx.getFrom() != null) {\n" +
                          "                long base = expectedNonces.computeIfAbsent(tx.getFrom(),\n" +
                          "                        addr -> state.getNonce(addr) + 1);\n" +
                          "                if (tx.getNonce() != base) {\n" +
                          "                    throw new IllegalArgumentException(\"Invalid nonce for \" + tx.getFrom() + \": expected \" + base + \" got \" + tx.getNonce());\n" +
                          "                }\n" +
                          "                expectedNonces.put(tx.getFrom(), base + 1);\n" +
                          "            }\n" +
                          "        }\n" +
                          "        for (Transaction tx : sortedTxs) {\n" +
                          "            validateTransaction(tx, true);\n" +
                          "        }";
        content = content.replace(applyOld, applyNew);

        // 5. Apply loop
        content = content.replace("// Apply transactions and create receipts\n        for (Transaction tx : block.getTransactions()) {",
                                  "// Apply transactions and create receipts\n        for (Transaction tx : sortedTxs) {");

        Files.write(p, content.getBytes("UTF-8"));
        System.out.println("Patched successfully");
    }
}
