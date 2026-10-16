"""One-shot: append the audit Maven profiles to pom.xml (idempotent).

Run from blockchain-java/:  py -I audit/tools/add_audit_profiles.py
None of the profiles is active by default, so the normal build is unchanged.
"""
import pathlib

POM = pathlib.Path("pom.xml")
COMMON = ("-DDEBUG=true -DSTORAGE_AES_KEY=00112233445566778899aabbccddeeff "
          "-Djdk.net.unixdomain.tmpdir=${project.build.directory} -Dnet.bytebuddy.experimental=true "
          "--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED "
          "--add-opens java.base/java.io=ALL-UNNAMED")

PROFILES = """
    <!-- ══════════════════════════════════════════════════════════════════════
         AUDIT PROFILES (docs/audit). None is active by default; the normal build
         is unchanged. Each produces evidence under target/ for audit/tools.
         ══════════════════════════════════════════════════════════════════════ -->
    <profiles>
        <!-- Real suite under PRODUCTION consensus defaults: no -DMIN_PEERS=1 and
             no -DREQUIRE_QUANTUM_SIG=false. Failures are recorded, not fatal. -->
        <profile>
            <id>audit-prod</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <argLine>${argLine} -Xmx2g -XX:+UseG1GC __COMMON__</argLine>
                            <testFailureIgnore>true</testFailureIgnore>
                            <reportsDirectory>${project.build.directory}/surefire-reports-prod</reportsDirectory>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- Mutation testing on the security-critical core (pit.target / pit.name). -->
        <profile>
            <id>audit-pit</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-maven</artifactId>
                        <version>1.15.3</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.pitest</groupId>
                                <artifactId>pitest-junit5-plugin</artifactId>
                                <version>1.2.1</version>
                            </dependency>
                        </dependencies>
                        <configuration>
                            <targetClasses>
                                <param>${pit.target}</param>
                            </targetClasses>
                            <targetTests>
                                <param>com.hybrid.blockchain.*</param>
                            </targetTests>
                            <excludedTestClasses>
                                <param>com.hybrid.blockchain.audit.*</param>
                                <param>*Benchmark</param>
                                <param>*EdgeReadinessProbe</param>
                                <param>*GossipNetworkTest</param>
                                <param>*ByzantineFaultInjectionTest</param>
                                <param>*MTLSSecureNetworkTest</param>
                                <param>*ConcurrencyStress*</param>
                                <param>*AdminEndpointsTest</param>
                            </excludedTestClasses>
                            <excludedGroups>
                                <param>audit-poc</param>
                                <param>benchmark</param>
                            </excludedGroups>
                            <threads>4</threads>
                            <timeoutConstant>10000</timeoutConstant>
                            <outputFormats>
                                <param>XML</param>
                                <param>CSV</param>
                            </outputFormats>
                            <timestampedReports>false</timestampedReports>
                            <reportsDirectory>${project.build.directory}/pit-reports/${pit.name}</reportsDirectory>
                            <jvmArgs>
                                <value>-Xmx1g</value>
                                <value>-DDEBUG=true</value>
                                <value>-DREQUIRE_QUANTUM_SIG=false</value>
                                <value>-DMIN_PEERS=1</value>
                                <value>-DSTORAGE_AES_KEY=00112233445566778899aabbccddeeff</value>
                                <value>-DNODE_PRIVATE_KEY=1111111111111111111111111111111111111111111111111111111111111111</value>
                                <value>-Djdk.net.unixdomain.tmpdir=${project.build.directory}</value>
                                <value>--add-opens</value><value>java.base/java.lang=ALL-UNNAMED</value>
                                <value>--add-opens</value><value>java.base/java.util=ALL-UNNAMED</value>
                            </jvmArgs>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- SpotBugs + FindSecBugs. -->
        <profile>
            <id>audit-static</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <version>4.8.6.6</version>
                        <configuration>
                            <effort>Max</effort>
                            <threshold>Low</threshold>
                            <failOnError>false</failOnError>
                            <xmlOutput>true</xmlOutput>
                            <plugins>
                                <plugin>
                                    <groupId>com.h3xstream.findsecbugs</groupId>
                                    <artifactId>findsecbugs-plugin</artifactId>
                                    <version>1.13.0</version>
                                </plugin>
                            </plugins>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- Error Prone compile (overwrites target/classes; run a normal compile after). -->
        <profile>
            <id>audit-errorprone</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                            <fork>true</fork>
                            <showWarnings>true</showWarnings>
                            <compilerArgs>
                                <arg>-XDcompilePolicy=simple</arg>
                                <arg>-Xmaxwarns</arg>
                                <arg>100000</arg>
                                <arg>-Xplugin:ErrorProne -XepDisableWarningsInGeneratedCode -XepAllErrorsAsWarnings</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
                                <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                                <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED</arg>
                            </compilerArgs>
                            <annotationProcessorPaths>
                                <path>
                                    <groupId>com.google.errorprone</groupId>
                                    <artifactId>error_prone_core</artifactId>
                                    <version>2.24.1</version>
                                </path>
                            </annotationProcessorPaths>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- CycloneDX SBOM. -->
        <profile>
            <id>audit-sbom</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.cyclonedx</groupId>
                        <artifactId>cyclonedx-maven-plugin</artifactId>
                        <version>2.7.11</version>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
""".replace("__COMMON__", COMMON)


def main():
    s = POM.read_text(encoding="utf-8")
    if "<id>audit-prod</id>" in s:
        print("profiles already present"); return
    body = s.rstrip()
    assert body.endswith("</project>"), "pom.xml does not end with </project>"
    s = body[: -len("</project>")].rstrip() + "\n" + PROFILES
    s = s.replace("<audit.excluded>audit-poc</audit.excluded>",
                  "<audit.excluded>audit-poc</audit.excluded>\n"
                  "        <pit.target>com.hybrid.blockchain.Crypto</pit.target>\n"
                  "        <pit.name>default</pit.name>", 1)
    POM.write_text(s, encoding="utf-8")
    print("audit profiles added")


if __name__ == "__main__":
    main()
