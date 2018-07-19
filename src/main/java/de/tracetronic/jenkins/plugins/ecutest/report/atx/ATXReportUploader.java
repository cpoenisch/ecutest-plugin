/*
 * Copyright (c) 2015-2018 TraceTronic GmbH
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice, this
 *      list of conditions and the following disclaimer.
 *
 *   2. Redistributions in binary form must reproduce the above copyright notice, this
 *      list of conditions and the following disclaimer in the documentation and/or
 *      other materials provided with the distribution.
 *
 *   3. Neither the name of TraceTronic GmbH nor the names of its
 *      contributors may be used to endorse or promote products derived from
 *      this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.tracetronic.jenkins.plugins.ecutest.report.atx;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.remoting.Callable;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.groovy.JsonSlurper;
import de.tracetronic.jenkins.plugins.ecutest.env.TestEnvInvisibleAction.TestType;
import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.report.AbstractReportPublisher;
import de.tracetronic.jenkins.plugins.ecutest.report.atx.installation.ATXConfig;
import de.tracetronic.jenkins.plugins.ecutest.report.atx.installation.ATXInstallation;
import de.tracetronic.jenkins.plugins.ecutest.report.trf.TRFPublisher;
import de.tracetronic.jenkins.plugins.ecutest.util.ATXUtil;
import de.tracetronic.jenkins.plugins.ecutest.util.validation.ATXValidator;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComClient;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComException;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComProperty;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.TestEnvironment;

/**
 * Class providing the generation and upload of {@link ATXReport}s.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class ATXReportUploader extends AbstractATXReportHandler {

    /**
     * Defines the API URL for linking ATX trend reports.
     */
    private static final String ATX_TREND_URL = "wicket/bookmarkable/"
            + "de.tracetronic.ttstm.web.detail.TestReportViewPage?testCase";

    /**
     * Instantiates a new {@code ATXReportUploader}.
     *
     * @param installation
     *            the ATX installation
     */
    public ATXReportUploader(final ATXInstallation installation) {
        super(installation);
    }

    /**
     * Generates and uploads {@link ATXReport}s.
     *
     * @param reportDirs
     *            the report directories
     * @param allowMissing
     *            specifies whether missing reports are allowed
     * @param run
     *            the run
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true} if upload succeeded, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    public boolean upload(final List<FilePath> reportDirs, final boolean allowMissing, final Run<?, ?> run,
            final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        final TTConsoleLogger logger = new TTConsoleLogger(listener);
        final List<ATXReport> atxReports = new ArrayList<ATXReport>();

        // Prepare ATX report information
        final EnvVars envVars = run.getEnvironment(listener);
        final ATXConfig config = getInstallation().getConfig();
        final String projectId = ATXUtil.getProjectId(config, envVars);
        final String baseUrl = ATXUtil.getBaseUrl(config, envVars);
        if (baseUrl == null) {
            logger.logError(String.format("Error getting base URL for selected TEST-GUIDE installation: %s",
                    getInstallation().getName()));
            return false;
        }

        int index = 0;
        for (final FilePath reportDir : reportDirs) {
            final FilePath reportFile = AbstractReportPublisher.getFirstReportFile(reportDir);
            if (reportFile != null && reportFile.exists()) {
                final List<FilePath> uploadFiles = Arrays.asList(
                        reportDir.list(TRFPublisher.TRF_INCLUDES, TRFPublisher.TRF_EXCLUDES));

                // Upload ATX reports
                TestInfoHolder testInfo = launcher.getChannel().call(
                        new UploadReportCallable(config, uploadFiles, envVars, listener));

                // Prepare ATX report links
                final String title = reportFile.getParent().getName();
                if (testInfo == null) {
                    testInfo = launcher.getChannel().call(new ParseTRFCallable(reportFile.getRemote()));
                }
                final String testName = testInfo.getTestName();
                final TestType testType = testInfo.getTestType();
                final String from = String.valueOf(testInfo.getFrom());
                final String to = String.valueOf(testInfo.getTo());
                index = traverseReports(atxReports, reportDir, index, title, baseUrl, from, to,
                        testName, testType, projectId);
            } else {
                if (allowMissing) {
                    continue;
                } else {
                    logger.logError(String.format("Specified TRF file '%s' does not exist.", reportFile));
                    return false;
                }
            }
        }

        if (atxReports.isEmpty() && !allowMissing) {
            logger.logError("Empty test results are not allowed, setting build status to FAILURE!");
            return false;
        }

        addBuildAction(run, atxReports);
        return true;
    }

    /**
     * Creates the main report and adds the sub-reports by traversing them recursively.
     *
     * @param atxReports
     *            the ATX reports
     * @param testReportDir
     *            the test report directory
     * @param id
     *            the report id
     * @param title
     *            the report title
     * @param baseUrl
     *            the base URL
     * @param from
     *            the from date
     * @param to
     *            the to date
     * @param testName
     *            the test name
     * @param testType
     *            the test type
     * @param projectId
     *            the project id
     * @return the current report id
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private int traverseReports(final List<ATXReport> atxReports, final FilePath testReportDir, int id,
            final String title, final String baseUrl, final String from, final String to, final String testName,
            final TestType testType, final String projectId) throws IOException, InterruptedException {
        // Prepare ATX report information
        String reportUrl = null;
        String trendReportUrl = null;
        final String atxTestName = ATXUtil.getValidATXName(testName);
        if (testType == TestType.PACKAGE) {
            reportUrl = getPkgReportUrl(baseUrl, from, to, atxTestName, projectId);
            trendReportUrl = getPkgTrendReportUrl(baseUrl, atxTestName, projectId);
        } else {
            reportUrl = getPrjReportUrl(baseUrl, from, to, atxTestName, null, projectId);
        }

        final ATXReport atxReport = new ATXReport(String.format("%d", ++id), title, reportUrl);
        if (trendReportUrl != null) {
            atxReport.addSubReport(new ATXReport(String.format("%d", ++id), title, trendReportUrl, true));
        }
        atxReports.add(atxReport);

        // Search for sub-reports
        final boolean isSingleTestplanMap = ATXUtil.isSingleTestplanMap(getInstallation().getConfig());
        if (isSingleTestplanMap) {
            id = traverseSubReports(atxReport, testReportDir, id, baseUrl, from, to, null, projectId);
        } else {
            id = traverseSubReports(atxReport, testReportDir, id, baseUrl, from, to, atxTestName, projectId);
        }
        return id;
    }

    /**
     * Builds a list of report files for ATX report generation and upload.
     * Includes the report files generated during separate sub-project execution.
     *
     * @param atxReport
     *            the ATX report
     * @param testReportDir
     *            the main test report directory
     * @param id
     *            the id increment
     * @param baseUrl
     *            the base URL
     * @param from
     *            the from date
     * @param to
     *            the to date
     * @param projectName
     *            the main project name, can be {@code null}
     * @param projectId
     *            the project id
     * @return the current id increment
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private int traverseSubReports(final ATXReport atxReport, final FilePath testReportDir, int id,
            final String baseUrl, final String from, final String to, final String projectName, final String projectId)
                    throws IOException, InterruptedException {
        for (final FilePath subDir : testReportDir.listDirectories()) {
            final FilePath reportFile = AbstractReportPublisher.getFirstReportFile(subDir);
            if (reportFile != null && reportFile.exists()) {
                // Prepare ATX report information for sub-report
                final String testName = reportFile.getParent().getName().replaceFirst("^Report\\s", "");
                final String atxTestName = ATXUtil.getValidATXName(testName);
                final String reportUrl = getPrjReportUrl(baseUrl, from, to, atxTestName, projectName, projectId);
                final ATXReport subReport = new ATXReport(String.format("%d", ++id), testName, reportUrl);
                atxReport.addSubReport(subReport);
                id = traverseSubReports(subReport, subDir, id, baseUrl, from, to, projectName, projectId);
            }
        }
        return id;
    }

    /**
     * Adds the {@link ATXBuildAction} to the build holding the found {@link ATXReport}s.
     *
     * @param run
     *            the run
     * @param atxReports
     *            the list of {@link ATXReport}s to add
     */
    @SuppressWarnings("unchecked")
    private void addBuildAction(final Run<?, ?> run, final List<ATXReport> atxReports) {
        ATXBuildAction<ATXReport> action = run.getAction(ATXBuildAction.class);
        if (action == null) {
            action = new ATXBuildAction<ATXReport>(false);
            run.addAction(action);
        }
        action.addAll(atxReports);
    }

    /**
     * Gets the package trend report URL.
     *
     * @param baseUrl
     *            the base URL
     * @param testName
     *            the test name
     * @param projectId
     *            the project id
     * @return the trend report URL
     */
    private String getPkgTrendReportUrl(final String baseUrl, final String testName, final String projectId) {
        String pkgTrendReportUrl = String.format("%s/%s=%s", baseUrl, ATX_TREND_URL, testName);
        if (projectId != null) {
            pkgTrendReportUrl = String.format("%s&projectId=%s", pkgTrendReportUrl, projectId);
        }
        return pkgTrendReportUrl;
    }

    /**
     * Gets the package report URL pre-filtered by a start and end date.
     *
     * @param baseUrl
     *            the base URL
     * @param from
     *            the start date
     * @param to
     *            the end date
     * @param testName
     *            the test name
     * @param projectId
     *            the project id
     * @return the report URL
     */
    private String getPkgReportUrl(final String baseUrl, final String from, final String to,
            final String testName, final String projectId) {
        String pkgReportUrl = String
                .format("%s/reports?dateFrom=%s&dateTo=%s&testcase=%s", baseUrl, from, to, testName);
        if (projectId != null) {
            pkgReportUrl = String.format("%s&projectId=%s", pkgReportUrl, projectId);
        }
        return pkgReportUrl;
    }

    /**
     * Gets the project report URL pre-filtered by a start and end date.
     *
     * @param baseUrl
     *            the base URL
     * @param from
     *            the start date
     * @param to
     *            the end date
     * @param testName
     *            the test name
     * @param projectName
     *            the main project name
     * @param projectId
     *            the project id
     * @return the report URL
     */
    private String getPrjReportUrl(final String baseUrl, final String from, final String to,
            final String testName, final String projectName, final String projectId) {
        String prjReportUrl;
        if (projectName != null) {
            prjReportUrl = String.format("%s/reports?dateFrom=%s&dateTo=%s&testexecplan=%s&plannedTestCaseFolder=%s*",
                    baseUrl, from, to, projectName, testName);
        } else {
            prjReportUrl = String.format("%s/reports?dateFrom=%s&dateTo=%s&testexecplan=%s",
                    baseUrl, from, to, testName);
        }
        if (projectId != null) {
            prjReportUrl = String.format("%s&projectId=%s", prjReportUrl, projectId);
        }

        return prjReportUrl;
    }

    /**
     * {@link Callable} enabling generating and uploading ATX reports remotely.
     */
    private static final class UploadReportCallable extends AbstractReportCallable<TestInfoHolder> {

        private static final long serialVersionUID = 1L;

        /**
         * File name of the error file which is created in case of an ATX upload error.
         */
        private static final String ERROR_FILE_NAME = "error.log.raw.json";

        /**
         * File name of the success file which is created in case of a regular ATX upload.
         * Will only be written by TEST-GUIDE 1.53.0 and above.
         */
        private static final String SUCCESS_FILE_NAME = "success.json";

        /**
         * Instantiates a new {@link UploadReportCallable}.
         *
         * @param config
         *            the ATX configuration
         * @param reportFiles
         *            the list of TRF files
         * @param envVars
         *            the environment variables
         * @param listener
         *            the listener
         */
        UploadReportCallable(final ATXConfig config, final List<FilePath> reportFiles, final EnvVars envVars,
                final TaskListener listener) {
            super(config, reportFiles, envVars, listener);
        }

        @Override
        public TestInfoHolder call() throws IOException {
            TestInfoHolder testInfo = null;
            final TTConsoleLogger logger = new TTConsoleLogger(getListener());
            final Map<String, String> configMap = getConfigMap(true);
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                final TestEnvironment testEnv = (TestEnvironment) comClient.getTestEnvironment();
                final List<FilePath> uploadFiles = getReportFiles();
                if (uploadFiles.isEmpty()) {
                    logger.logInfo("-> No report files found to upload!");
                } else {
                    for (final FilePath uploadFile : uploadFiles) {
                        logger.logInfo(String.format("-> Generating and uploading ATX report: %s",
                                uploadFile.getRemote()));
                        final FilePath outDir = uploadFile.getParent().child(ATX_TEMPLATE_NAME);
                        testEnv.generateTestReportDocumentFromDB(uploadFile.getRemote(),
                                outDir.getRemote(), ATX_TEMPLATE_NAME, true, configMap);
                        comClient.waitForIdle(0);

                        final FilePath successFile = outDir.child(SUCCESS_FILE_NAME);
                        if (testInfo == null) {
                            testInfo = checkSuccessLog(successFile, uploadFile, logger);
                        }

                        final FilePath errorFile = outDir.child(ERROR_FILE_NAME);
                        checkErrorLog(errorFile, logger);
                    }
                }
            } catch (final ETComException e) {
                logger.logComException(e.getMessage());
            }
            return testInfo;
        }

        /**
         * Checks the success log file and parse upload information.
         * The success log file will only be written by TEST-GUIDE 1.53.0 and above.
         *
         * @param successFile
         *            the success file
         * @param uploadFile
         *            the upload file
         * @param logger
         *            the logger
         * @return the parsed test information
         * @throws IOException
         *             signals that an I/O exception has occurred
         * @throws MalformedURLException
         *             in case of a malformed URL
         * @throws UnsupportedEncodingException
         *             in case of an unsupported encoding
         */
        private TestInfoHolder checkSuccessLog(final FilePath successFile, final FilePath uploadFile,
                final TTConsoleLogger logger) throws IOException, MalformedURLException, UnsupportedEncodingException {
            TestInfoHolder testInfo = null;
            try {
                if (successFile.exists()) {
                    logger.logDebug("Uploading ATX report succeded:");
                    final JSONObject jsonObject = (JSONObject) new JsonSlurper()
                    .parseText(successFile.readToString());
                    final JSONArray jsonArray = jsonObject.optJSONArray("ENTRIES");
                    if (jsonArray != null) {
                        for (int i = 0; i < jsonArray.size(); i++) {
                            final String status = jsonArray.getJSONObject(i).getString("STATUS");
                            if ("200".equals(status)) {
                                final String file = jsonArray.getJSONObject(i).getString("FILE");
                                final String text = jsonArray.getJSONObject(i).getString("TEXT");
                                logger.logDebug(String.format("%s: %s - %s", status, file, text));

                                final URL location = resolveRedirect(text);
                                testInfo = parseTestInfo(location, uploadFile);
                                break;
                            }
                        }
                    }
                }
            } catch (final JSONException | InterruptedException | URISyntaxException
                    | KeyManagementException | NoSuchAlgorithmException e) {
                logger.logError("-> Could not parse ATX JSON response: " + e.getMessage());
            }
            return testInfo;
        }

        /**
         * Checks the error log file and aborts the upload if any.
         *
         * @param errorFile
         *            the error file
         * @param logger
         *            the logger
         * @throws IOException
         *             signals that an I/O exception has occurred
         */
        private void checkErrorLog(final FilePath errorFile, final TTConsoleLogger logger) throws IOException {
            try {
                if (errorFile.exists()) {
                    logger.logError("Error while uploading ATX report:");
                    final JSONObject jsonObject = (JSONObject) new JsonSlurper()
                    .parseText(errorFile.readToString());
                    final JSONArray jsonArray = jsonObject.optJSONArray("ENTRIES");
                    if (jsonArray != null) {
                        for (int i = 0; i < jsonArray.size(); i++) {
                            final String file = jsonArray.getJSONObject(i).getString("FILE");
                            final String status = jsonArray.getJSONObject(i).getString("STATUS");
                            final String text = jsonArray.getJSONObject(i).getString("TEXT");
                            logger.logError(String.format("%s: %s - %s", status, file, text));
                        }
                    }
                }
            } catch (final JSONException | InterruptedException e) {
                logger.logError("-> Could not parse ATX JSON response: " + e.getMessage());
            }
        }

        /**
         * Parses the test information from the URL parameters.
         *
         * @param url
         *            the URL location
         * @param uploadFile
         *            the upload file
         * @return the test info holder
         * @throws URISyntaxException
         *             the URI syntax exception
         * @throws UnsupportedEncodingException
         *             the unsupported encoding exception
         */
        private TestInfoHolder parseTestInfo(final URL url, final FilePath uploadFile)
                throws URISyntaxException, UnsupportedEncodingException {
            final Map<String, String> params = splitQuery(url);
            if (params.isEmpty()) {
                return null;
            }

            String testName = params.get("testexecplan");
            TestType testType = TestType.PROJECT;
            if ("SinglePackageExecution".equals(testName)) {
                testType = TestType.PACKAGE;
                testName = uploadFile.getBaseName();
            }
            final long from = Long.parseLong(params.get("dateFrom"));
            final long to = Long.parseLong(params.get("dateTo"));

            return new TestInfoHolder(testName, testType, from, to);
        }

        /**
         * Returns the URL query parameters as map.
         *
         * @param url
         *            the URL
         * @return the parameter map
         * @throws UnsupportedEncodingException
         *             in case of an unsupported encoding
         */
        private Map<String, String> splitQuery(final URL url) throws UnsupportedEncodingException {
            final Map<String, String> queryMap = new LinkedHashMap<String, String>();
            final String query = url.getQuery();
            final String[] pairs = query.split("&");
            for (final String pair : pairs) {
                final int idx = pair.indexOf('=');
                queryMap.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
            }
            return queryMap;
        }

        /**
         * Resolves the given URL redirect.
         *
         * @param redirect
         *            the redirect URL
         * @return the resolved URL redirect
         * @throws MalformedURLException
         *             in case of a malformed URL
         * @throws NoSuchAlgorithmException
         *             in case of a missing algorithm
         * @throws KeyManagementException
         *             in case of a key management exception
         * @throws IOException
         *             signals that an I/O exception has occurred
         */
        private URL resolveRedirect(final String redirect)
                throws MalformedURLException, NoSuchAlgorithmException, KeyManagementException, IOException {
            HttpURLConnection connection = null;
            final URL url = new URL(redirect);

            // Handle SSL connection
            if (redirect.startsWith("https://")) {
                ATXValidator.initSSLConnection();
                connection = (HttpsURLConnection) url.openConnection();
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setInstanceFollowRedirects(false);
            connection.connect();
            final String location = connection.getHeaderField("Location");
            return new URL(location);
        }
    }

    /**
     * {@link Callable} parsing the test name, type and execution times of a TRF remotely.
     */
    private static final class ParseTRFCallable extends MasterToSlaveCallable<TestInfoHolder, IOException> {

        private static final long serialVersionUID = 1L;

        private final String trfFile;

        /**
         * Instantiates a new {@link ParseTRFCallable}.
         *
         * @param trfFile
         *            the TRF file path
         */
        ParseTRFCallable(final String trfFile) {
            this.trfFile = trfFile;
        }

        @Override
        public TestInfoHolder call() throws IOException {
            try (SQLite sql = new SQLite(trfFile)) {
                ResultSet rs = sql.query("SELECT execution_time, duration from info");
                final String execTime = rs.getString("execution_time");
                final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                final Date date = fmt.parse(execTime);
                final float duration = rs.getFloat("duration") * 1000.0f;
                final long from = (long) date.getTime();
                final long to = from + (long) duration;

                rs = sql.query("SELECT name FROM prj");
                final String prjName = rs.getString("name");
                if ("$$$_PACKAGE_$$$".equals(prjName)) {
                    rs = sql.query("SELECT name FROM pkg");
                    final String pkgName = rs.getString("name");
                    return new TestInfoHolder(pkgName, TestType.PACKAGE, from, to);
                } else {
                    return new TestInfoHolder(prjName, TestType.PROJECT, from, to);
                }
            } catch (final ClassNotFoundException | SQLException | ParseException e) {
                throw new IOException(e);
            }
        }

        /**
         * Parser for SQLite databases like TRF reports.
         */
        private static class SQLite implements AutoCloseable {

            private final Connection connection;
            private final Statement statement;

            /**
             * Instantiates a new {@link SQLite}.
             *
             * @param sqlFile
             *            the path to database file
             * @throws ClassNotFoundException
             *             in case the JDBC class cannot be located
             * @throws SQLException
             *             in case of a SQL exception
             */
            SQLite(final String sqlFile) throws ClassNotFoundException, SQLException {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + sqlFile);
                statement = connection.createStatement();
            }

            /**
             * Queries the database with given SQL statement.
             *
             * @param sql
             *            the SQL statement
             * @return the result set
             * @throws SQLException
             *             in case of a SQL exception
             */
            public ResultSet query(final String sql) throws SQLException {
                return statement.executeQuery(sql);
            }

            @Override
            public void close() throws SQLException {
                if (statement != null) {
                    statement.close();
                }
                if (connection != null) {
                    connection.close();
                }
            }
        }
    }

    /**
     * Helper class storing information about the test name and type.
     * Used as data model for {@link ParseTRFCallable}.
     */
    private static final class TestInfoHolder implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String testName;
        private final TestType testType;
        private final long from;
        private final long to;

        /**
         * Instantiates a new {@link TestInfoHolder}.
         *
         * @param testName
         *            the test name
         * @param testType
         *            the test type
         * @param from
         *            the starting execution time
         * @param to
         *            the finishing execution time
         */
        TestInfoHolder(final String testName, final TestType testType, final long from, final long to) {
            this.testName = testName;
            this.testType = testType;
            this.from = from;
            this.to = to;
        }

        /**
         * @return the test name
         */
        public String getTestName() {
            return testName;
        }

        /**
         * @return the test type
         */
        public TestType getTestType() {
            return testType;
        }

        /**
         * @return the from date
         */
        public long getFrom() {
            return from;
        }

        /**
         * @return the to date
         */
        public long getTo() {
            return to;
        }
    }
}
