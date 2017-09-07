package io.fabric8.maven.docker.config.handler.compose;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.config.RestartPolicy;
import io.fabric8.maven.docker.config.RunImageConfiguration;
import io.fabric8.maven.docker.config.RunVolumeConfiguration;
import io.fabric8.maven.docker.config.handler.ExternalConfigHandlerException;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mocked;
import org.apache.commons.io.FileUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenReaderFilter;
import org.apache.maven.shared.filtering.MavenReaderFilterRequest;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author roland
 * @since 28.08.17
 */
public class DockerComposeConfigHandlerTest {

    @Injectable
    ImageConfiguration unresolved;

    @Mocked
    MavenProject project;

    @Mocked
    MavenSession session;

    @Mocked
    MavenReaderFilter readerFilter;

    private DockerComposeConfigHandler handler;

    @Before
    public void setUp() throws Exception {
        handler = new DockerComposeConfigHandler();
        handler.readerFilter = readerFilter;
    }


    @Test
    public void simple() throws IOException, MavenFilteringException {
        setupComposeExpectations("docker-compose.yml");
        List<ImageConfiguration> configs = handler.resolve(unresolved, project, session);
        assertEquals(1, configs.size());
        validateRunConfiguration(configs.get(0).getRunConfiguration());
    }

    @Test
    public void positiveVersionTest() throws IOException, MavenFilteringException {
        for (String composeFile : new String[] { "version/compose-version-2.yml", "version/compose-version-2x.yml"} ) {
            setupComposeExpectations(composeFile);
            assertNotNull(handler.resolve(unresolved, project, session));
        }

    }

    @Test
    public void negativeVersionTest() throws IOException, MavenFilteringException {
        for (String composeFile : new String[] { "version/compose-wrong-version.yml", "version/compose-no-version.yml"} ) {
            try {
                setupComposeExpectations(composeFile);
                handler.resolve(unresolved, project, session);
                fail();
            } catch (ExternalConfigHandlerException exp) {
                assertTrue(exp.getMessage().contains(("2.x")));
            }
        }

    }

    private void setupComposeExpectations(final String file) throws IOException, MavenFilteringException {
        new Expectations() {{
            final File input = getAsFile("/compose/" + file);

            unresolved.getExternalConfig();
            result = new HashMap<String,String>() {{
                put("composeFile", input.getAbsolutePath());
                // provide a base directory that actually exists, so that relative paths referenced by the
                // docker-compose.yaml file can be resolved
                // (note: this is different than the directory returned by 'input.getParent()')
                put("basedir", this.getClass().getResource("/").getFile());
            }};

            readerFilter.filter((MavenReaderFilterRequest) any);
            result = new FileReader(input);
        }};
    }

    private File getAsFile(String resource) throws IOException {
        File tempFile = File.createTempFile("compose",".yml");
        InputStream is = getClass().getResourceAsStream(resource);
        FileUtils.copyInputStreamToFile(is,tempFile);
        return tempFile;
    }


     void validateRunConfiguration(RunImageConfiguration runConfig) {

        validateVolumeConfig(runConfig.getVolumeConfiguration());

        assertEquals(a("CAP"), runConfig.getCapAdd());
        assertEquals(a("CAP"), runConfig.getCapDrop());
        assertEquals("command.sh", runConfig.getCmd().getShell());
        assertEquals(a("8.8.8.8"), runConfig.getDns());
        assertEquals(a("example.com"), runConfig.getDnsSearch());
        assertEquals("domain.com", runConfig.getDomainname());
        assertEquals("entrypoint.sh", runConfig.getEntrypoint().getShell());
        assertEquals(a("localhost:127.0.0.1"), runConfig.getExtraHosts());
        assertEquals("subdomain", runConfig.getHostname());
        assertEquals(a("redis","link1"), runConfig.getLinks());
        assertEquals((Long) 1L, runConfig.getMemory());
        assertEquals((Long) 1L, runConfig.getMemorySwap());
        assertEquals(RunImageConfiguration.NamingStrategy.none, runConfig.getNamingStrategy());
        assertEquals(null,runConfig.getEnvPropertyFile());

        assertEquals(null, runConfig.getPortPropertyFile());
        assertEquals(a("8081:8080"), runConfig.getPorts());
        assertEquals(true, runConfig.getPrivileged());
        assertEquals("tomcat", runConfig.getUser());
        assertEquals(a("from"), runConfig.getVolumeConfiguration().getFrom());
        assertEquals("foo", runConfig.getWorkingDir());

        validateEnv(runConfig.getEnv());

        // not sure it's worth it to implement 'equals/hashcode' for these
        RestartPolicy policy = runConfig.getRestartPolicy();
        assertEquals("on-failure", policy.getName());
        assertEquals(1, policy.getRetry());
    }

    void validateVolumeConfig(RunVolumeConfiguration toValidate) {
        final int expectedBindCnt = 4;
        final List<String> binds = toValidate.getBind();
        assertEquals("Expected " + expectedBindCnt + " bind statements", expectedBindCnt, binds.size());

        assertEquals(a("/foo", "/tmp:/tmp:rw", "namedvolume:/volume:ro"), binds.subList(0, expectedBindCnt - 1));

        String relativeVolumePath = binds.get(expectedBindCnt - 1);
        validateRelativeVolumeBindString(relativeVolumePath);
    }

    private void validateRelativeVolumeBindString(String relativeBindString) {
        System.err.println(">>>> " + relativeBindString);

        // A regex that matches both windows platform paths and unix style paths:
        // C:\Users\foo\Documents\workspaces\docker-maven-plugin\docker-maven-plugin\target\test-classes\compose\version:/tmp/version
        // and
        // /Users/foo/workspaces/docker-maven-plugin/target/test-classes/compose/version:/tmp/version

        String regex = "^([A-Z]|/).*compose[\\\\|/]version:.*";

        assertTrue(relativeBindString.matches(regex));
        assertTrue(new File(relativeBindString.split(":")[0]).exists());
    }

    protected void validateEnv(Map<String, String> env) {
        assertEquals(2, env.size());
        assertEquals("name", env.get("NAME"));
        assertEquals("true", env.get("BOOL"));
    }

    protected List<String> a(String ... args) {
        return Arrays.asList(args);
    }

    @Test
    public void testRegex() throws Exception {
        String toMatch = "C:\\Users\\khanson5\\Documents\\GitHub\\docker-maven-plugin-karen\\docker-maven-plugin-karen\\target\\test-classes\\compose\\version:/tmp/version";
        assertTrue(toMatch.matches("^([A-Z]|/).*compose[\\\\|/]version:.*"));
    }
}
