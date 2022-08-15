/*******************************************************************************
 * (c) Copyright IBM Corporation 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.wlp.test.dev.it;

import static org.junit.Assert.*;
import static io.openliberty.tools.common.plugins.util.BinaryScannerUtil.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * scanner acceptance tests
 */
public class ScannerTest extends BaseScannerTest {

    @Before
    public void setUp() throws Exception {
        setUpBeforeTest("../resources/basic-dev-project9");
    }

    @After
    public void cleanUp() throws Exception {
        cleanUpAfterTest();
    }

    /**
     * The user must record the expected binary scanner version in the pom.xml file.
     * This tests that the expected version is actually pulled in by the plugin.
     * 
     * @throws Exception
     */
    @Test
    public void versionTest() throws Exception {
        String expectedVersion = System.getProperty("scannerVersion");
        assertNotNull("The <scannerVersion> is not set in the pom.", expectedVersion);
        assertTrue("The <scannerVersion> set in the pom is empty.", expectedVersion.length() > 0);
        // Must differentiate between 22.0.0.3.jar and 22.0.0.3-SNAPSHOT.jar
        String expectedJarName = "binary-app-scanner-" + expectedVersion + ".jar";
        runCompileAndGenerateFeatures("-X");
        // Find "[DEBUG] Calling binary-app-scanner-22.0.0.3-SNAPSHOT.jar with the following inputs..."
        String line = findLogMessage("with the following inputs...", 10000, logFile);
        assertNotNull("Calling binary-app-scanner... line not found in log file", line);
        assertTrue("The <scannerVersion> set in the pom:" + expectedVersion + " not found in log:\"" + line + "\"", 
            line.contains(expectedJarName));
    }

    /**
     * Verify a scanner log is generated when plugin logging is enabled.
     * 
     * @throws Exception
     */
    @Test
    public void scannerLogExistenceTest() throws Exception {
        File scannerLogDir = new File(targetDir, "logs");
        assertFalse(scannerLogDir.exists());

        runCompileAndGenerateFeatures("-X");
        assertTrue(scannerLogDir.exists());
        File[] logDirListing = scannerLogDir.listFiles();
        assertNotNull(logDirListing);
        boolean logExists = false;
        for (File child : logDirListing) {
            if (child.exists() && child.length() > 0) {
                logExists = true;
            }
        }
        assertTrue(logExists);
    }

    /**
     * Verify that when a feature already exists in server.xml that it is 
     * not generated when generation is called.
     * 
     * @throws Exception
     */
    @Test
    public void featureUniqueTest() throws Exception {
        String testFeature = "restfulWS-3.0";
        replaceString("<!--replaceable-->",
            "<featureManager>\n" +
                "<feature>" + testFeature + "</feature>\n" +
                "</featureManager>\n",
            serverXmlFile);
        runCompileAndGenerateFeatures();

        assertTrue(newFeatureFile.exists());
        Set<String> generatedFeatures = readFeatures(newFeatureFile);
        assertFalse(generatedFeatures.contains(testFeature));
    }

    /**
     * Verify FeatureConflictException is thrown by checking for
     * BINARY_SCANNER_CONFLICT_MESSAGE1 (conflict between configured features
     * and API usage)
     * 
     * @throws Exception
     */
    @Test
    public void featureConflictExceptionTest() throws Exception {
        // place conflicting feature cdi-1.2 in server.xml
        replaceString("<!--replaceable-->",
            "<featureManager>\n" +
                "<feature>cdi-1.2</feature>\n" +
                "</featureManager>\n",
            serverXmlFile);
        runCompileAndGenerateFeatures();

        // Verify BINARY_SCANNER_CONFLICT_MESSAGE1 is shown.
        Set<String> recommendedFeatureSet = new HashSet<String>();
        recommendedFeatureSet.addAll(getExpectedGeneratedFeaturesSet());
        recommendedFeatureSet.add("cdi-3.0");
        String expectedMessage = String.format(BINARY_SCANNER_CONFLICT_MESSAGE1, getCdi12ConflictingFeatures(), recommendedFeatureSet);
        assertTrue("Could not find the feature conflict message in the process output.\n" + formatOutput(processOutput),
            processOutput.contains(expectedMessage));
    }

    /**
     * Verify ProvidedFeatureConflictException is thrown by checking
     * for BINARY_SCANNER_CONFLICT_MESSAGE2 (conflict between configured features)
     * 
     * @throws Exception
     */
    @Test
    public void providedFeatureConflictExceptionTest() throws Exception {
        // place expected generated features in server.xml and conflicting cdi-1.2
        String featureManagerString = "<featureManager>\n" +
            getExpectedFeatureElementString() +
            "<feature>cdi-1.2</feature>\n" +
            "</featureManager>\n";

        replaceString("<!--replaceable-->", featureManagerString, serverXmlFile);
        runCompileAndGenerateFeatures();

        // Verify BINARY_SCANNER_CONFLICT_MESSAGE2 error is shown
        Set<String> expectedReportedConflictSet = new HashSet<String>();
        expectedReportedConflictSet.add("restfulWS-3.0");
        // TODO: already asked scanner team why servlet-5.0 is not shown. Should be added here.
        expectedReportedConflictSet.add("cdi-1.2");
        Set<String> recommendedFeatureSet = new HashSet<String>();
        recommendedFeatureSet.addAll(getExpectedGeneratedFeaturesSet());
        String expectedMessage = String.format(BINARY_SCANNER_CONFLICT_MESSAGE2, expectedReportedConflictSet, recommendedFeatureSet);
        assertTrue("Could not find the feature conflict message in the process output.\n " + formatOutput(processOutput),
            processOutput.contains(expectedMessage));
    }

    /**
     * FeatureNotAvailableAtRequestedLevelException flags conflict between
     * required features in API usage or configured features and MP/EE level specified.
     * Check for BINARY_SCANNER_CONFLICT_MESSAGE5 (feature unavailable for required MP/EE levels)
     * 
     * @throws Exception
     */
    @Test
    public void featureUnavailableConflictTest() throws Exception {
        setUpBeforeTest("../resources/basic-dev-project8");
        // change MP 4.1 to MP 1.2
        modifyMPVersion("4.1", "1.2");

        // add mpOpenAPI-1.0 feature to server.xml, not available in MP 1.2
        replaceString("<!--replaceable-->",
            "<featureManager>\n" +
                "<feature>mpOpenAPI-1.0</feature>\n" +
                "</featureManager>\n",
            serverXmlFile);
        runCompileAndGenerateFeatures();

        Set<String> conflictingFeatureSet = getEE8ExpectedGeneratedFeaturesSet();
        conflictingFeatureSet.add("mpOpenAPI-1.0");
        Set<String> removeFeatures = new HashSet<String>(Arrays.asList("mpOpenAPI"));
        assertTrue("Could not find the feature conflict message in the process output.\n " + processOutput,
            processOutput.contains(
                String.format(BINARY_SCANNER_CONFLICT_MESSAGE5, conflictingFeatureSet, "mp1.2", "ee8", removeFeatures)));
    }

    // TODO FeatureModifiedException
    // TODO java.lang.IllegalArgumentException - targetJavaEE N.B. tested in DevTest.java
    // TODO java.lang.IllegalArgumentException - targetMicroProfile N.B. tested in DevTest.java

    /**
     * Test calling the scanner with both the EE umbrella dependency and the MP
     * umbrella dependency.
     * 
     * @throws Exception
     */
    @Test
    public void bothEEMPUmbrellaTest() throws Exception {
        runCompileAndGenerateFeatures("-X");
        // Check for "  targetJavaEE: null" in the debug output
        String line = findLogMessage(TARGET_EE_NULL, 3000, logFile);
        assertNull("Target EE:" + line, line);
        // Check for "  targetJavaMP: null" in the debug output
        line = findLogMessage(TARGET_MP_NULL, 3000, logFile);
        assertNull("Target MP:" + line, line);
    }

    /**
     * Test calling the scanner with just the EE umbrella dependency and no MP
     * umbrella dependency.
     * 
     * @throws Exception
     */
    @Test
    public void onlyEEUmbrellaTest() throws Exception {
        replaceString(MP_UMBRELLA, ESA_MP_DEPENDENCY, pom);
        runCompileAndGenerateFeatures("-X");
        // Check for "  targetJavaEE: null" in the debug output
        String line = findLogMessage(TARGET_EE_NULL, 3000, logFile);
        assertNull("Target EE:" + line, line);
        // Check for "  targetJavaMP: null" in the debug output
        line = findLogMessage(TARGET_MP_NULL, 3000, logFile);
        assertNotNull("Target MP:" + line, line);
    }

    /**
     * Test calling the scanner with just the MP umbrella dependency and no EE
     * umbrella dependency.
     * 
     * @throws Exception
     */
    @Test
    public void onlyMPUmbrellaTest() throws Exception {
        replaceString(EE_UMBRELLA, ESA_EE_DEPENDENCY, pom);
        runCompileAndGenerateFeatures("-X");
        // Check for "  targetJavaEE: null" in the debug output
        String line = findLogMessage(TARGET_EE_NULL, 3000, logFile);
        assertNotNull("Target EE:" + line, line);
        // Check for "  targetJavaMP: null" in the debug output
        line = findLogMessage(TARGET_MP_NULL, 3000, logFile);
        assertNull("Target MP:" + line, line);
    }

    /**
     * Test calling the scanner with no EE umbrella dependency and no MP
     * umbrella dependency.
     * 
     * @throws Exception
     */
    @Test
    public void noUmbrellaTest() throws Exception {
        replaceString(EE_UMBRELLA, ESA_EE_DEPENDENCY, pom);
        replaceString(MP_UMBRELLA, ESA_MP_DEPENDENCY, pom);
        runCompileAndGenerateFeatures("-X");
        // Check for "  targetJavaEE: null" in the debug output
        String line = findLogMessage(TARGET_EE_NULL, 3000, logFile);
        assertNotNull("Target EE:" + line, line);
        // Check for "  targetJavaMP: null" in the debug output
        line = findLogMessage(TARGET_MP_NULL, 3000, logFile);
        assertNotNull("Target MP:" + line, line);
    }

    // get the app features that conflict with cdi-1.2
    protected Set<String> getCdi12ConflictingFeatures() {
        // restfulWS-3.0 and servlet-5.0 (EE9) conflicts with cdi-1.2 (EE7)
        Set<String> conflictingFeatures = new HashSet<String>();
        conflictingFeatures.add("restfulWS-3.0");
        conflictingFeatures.add("servlet-5.0");
        conflictingFeatures.add("mpHealth-4.0");
        conflictingFeatures.add("cdi-1.2");
        return conflictingFeatures;
    }

    protected Set<String> getExpectedGeneratedFeaturesSet() {
        return new HashSet<String>(Arrays.asList("restfulWS-3.0", "servlet-5.0", "mpHealth-4.0"));
    }

    protected Set<String> getEE8ExpectedGeneratedFeaturesSet() {
        return new HashSet<String>(Arrays.asList("servlet-4.0", "jaxrs-2.1"));
    }

    // get the expected features in string format to insert in server.xml featureManager block
    private String getExpectedFeatureElementString() {
        Set<String> expectedFeatures = getExpectedGeneratedFeaturesSet();
        StringBuilder str = new StringBuilder();
        for (String feat : expectedFeatures) {
            str.append("  <feature>"+ feat + "</feature>\n");
        }
        return str.toString();
    }

    // change MP version in pom file
    protected void modifyMPVersion(String currentVersion, String updatedVersion) throws IOException { 
        replaceString("<artifactId>microprofile</artifactId>\n" + 
        "        <version>" + currentVersion + "</version>", "<artifactId>microprofile</artifactId>\n" + 
        "        <version>" + updatedVersion + "</version>", pom);
    }
}