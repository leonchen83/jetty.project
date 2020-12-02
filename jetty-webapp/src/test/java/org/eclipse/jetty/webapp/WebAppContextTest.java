//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.GenericServlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class WebAppContextTest
{
    public static final Logger LOG = Log.getLogger(WebAppContextTest.class);
    public WorkDir workDir;
    private final List<Object> lifeCycles = new ArrayList<>();

    @AfterEach
    public void tearDown()
    {
        lifeCycles.forEach(LifeCycle::stop);
    }

    private Server newServer()
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        lifeCycles.add(server);
        return server;
    }

    @Test
    public void testConfigurationClassesFromDefault()
    {
        Server server = newServer();
        // test if no classnames set, its the defaults
        WebAppContext wac = new WebAppContext();
        assertEquals(0, wac.getConfigurations().length);
        String[] classNames = wac.getConfigurationClasses();
        assertNotNull(classNames);

        // test if no classname set, and none from server its the defaults
        wac.setServer(server);
        assertTrue(Arrays.equals(classNames, wac.getConfigurationClasses()));
    }

    @Test
    public void testConfigurationClassesExplicit()
    {
        String[] classNames = {"x.y.z"};

        Server server = newServer();
        server.setAttribute(Configuration.ATTR, classNames);

        //test an explicitly set classnames list overrides that from the server
        WebAppContext wac = new WebAppContext();
        String[] myClassNames = {"a.b.c", "d.e.f"};
        wac.setConfigurationClasses(myClassNames);
        wac.setServer(server);
        String[] names = wac.getConfigurationClasses();
        assertTrue(Arrays.equals(myClassNames, names));

        //test if no explicit classnames, they come from the server
        WebAppContext wac2 = new WebAppContext();
        wac2.setServer(server);
        try
        {
            wac2.loadConfigurations();
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
        assertTrue(Arrays.equals(classNames, wac2.getConfigurationClasses()));
    }

    @Test
    public void testConfigurationInstances()
    {
        Configuration[] configs = {new WebInfConfiguration()};
        WebAppContext wac = new WebAppContext();
        wac.setConfigurations(configs);
        assertTrue(Arrays.equals(configs, wac.getConfigurations()));

        //test that explicit config instances override any from server
        String[] classNames = {"x.y.z"};
        Server server = newServer();
        server.setAttribute(Configuration.ATTR, classNames);
        wac.setServer(server);
        assertTrue(Arrays.equals(configs, wac.getConfigurations()));
    }

    @Test
    public void testRealPathDoesNotExist() throws Exception
    {
        Server server = newServer();
        WebAppContext context = new WebAppContext(".", "/");
        server.setHandler(context);
        server.start();

        ServletContext ctx = context.getServletContext();
        assertNotNull(ctx.getRealPath("/doesnotexist"));
        assertNotNull(ctx.getRealPath("/doesnotexist/"));
    }

    /**
     * tests that the servlet context white list works
     *
     * @throws Exception on test failure
     */
    @Test
    public void testContextWhiteList() throws Exception
    {
        Server server = newServer();
        HandlerList handlers = new HandlerList();
        WebAppContext contextA = new WebAppContext(".", "/A");

        ServletHolder servletAHolder = new ServletHolder(new GenericServlet()
        {
            @Override
            public void service(ServletRequest req, ServletResponse res)
            {
                this.getServletContext().getContext("/A/s");
            }
        });
        contextA.addServlet(servletAHolder, "/s");
        handlers.addHandler(contextA);
        WebAppContext contextB = new WebAppContext(".", "/B");

        ServletHolder servletBHolder = new ServletHolder(new GenericServlet()
        {
            @Override
            public void service(ServletRequest req, ServletResponse res)
            {
                this.getServletContext().getContext("/B/s");
            }
        });
        contextB.addServlet(servletBHolder, "/s");
        contextB.setContextWhiteList(new String[]{"/doesnotexist", "/B/s"});
        handlers.addHandler(contextB);

        server.setHandler(handlers);
        server.start();

        // context A should be able to get both A and B servlet contexts
        assertNotNull(contextA.getServletHandler().getServletContext().getContext("/A/s"));
        assertNotNull(contextA.getServletHandler().getServletContext().getContext("/B/s"));

        // context B has a contextWhiteList set and should only be able to get ones that are approved
        assertNull(contextB.getServletHandler().getServletContext().getContext("/A/s"));
        assertNotNull(contextB.getServletHandler().getServletContext().getContext("/B/s"));
    }

    @Test
    public void testAlias() throws Exception
    {
        Path tempDir = workDir.getEmptyPathDir().resolve("dir");
        FS.ensureEmpty(tempDir);

        Path webinf = tempDir.resolve("WEB-INF");
        FS.ensureEmpty(webinf);

        Path classes = tempDir.resolve("classes");
        FS.ensureEmpty(classes);

        Path someClass = classes.resolve("SomeClass.class");
        FS.touch(someClass);

        WebAppContext context = new WebAppContext();
        context.setBaseResource(new PathResource(tempDir));

        context.setResourceAlias("/WEB-INF/classes/", "/classes/");

        assertTrue(Resource.newResource(context.getServletContext().getResource("/WEB-INF/classes/SomeClass.class")).exists());
        assertTrue(Resource.newResource(context.getServletContext().getResource("/classes/SomeClass.class")).exists());
    }

    @Test
    public void testIsProtected()
    {
        WebAppContext context = new WebAppContext();

        assertTrue(context.isProtectedTarget("/web-inf/lib/foo.jar"));
        assertTrue(context.isProtectedTarget("/meta-inf/readme.txt"));
        assertFalse(context.isProtectedTarget("/something-else/web-inf"));
    }

    @Test
    public void testNullPath() throws Exception
    {
        Server server = newServer();

        HandlerList handlers = new HandlerList();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResource(new PathResource(testWebapp));
        context.setContextPath("/");
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        String rawResponse = connector.getResponse("GET http://localhost:8080 HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response OK", response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testNullSessionAndSecurityHandler() throws Exception
    {
        Server server = newServer();

        HandlerList handlers = new HandlerList();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext(null, null, null, null, null, new ErrorPageErrorHandler(),
            ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");

        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResource(new PathResource(testWebapp));
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();
        assertTrue(context.isAvailable());
    }

    @Test
    public void testBaseResourceAbsolutePath() throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");

        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        warPath = warPath.toAbsolutePath();
        assertTrue(warPath.isAbsolute(), "Path should be absolute: " + warPath);
        // Use String reference to war
        // On Unix / Linux this should have no issue.
        // On Windows with fully qualified paths such as "E:\mybase\webapps\dump.war" the
        // resolution of the Resource can trigger various URI issues with the "E:" portion of the provided String.
        context.setResourceBase(warPath.toString());

        server.setHandler(context);
        server.start();

        assertTrue(context.isAvailable(), "WebAppContext should be available");
    }

    public static Stream<Arguments> extraClasspathGlob()
    {
        List<Arguments> references = new ArrayList<>();

        Path extLibs = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibs = extLibs.toAbsolutePath();

        // Absolute reference with trailing slash and glob
        references.add(Arguments.of("absolute extLibs with glob", extLibs.toString() + File.separator + "*"));

        // Establish a relative extraClassPath reference
        String relativeExtLibsDir = MavenTestingUtils.getBasePath().relativize(extLibs).toString();

        // This will be in the String form similar to "src/test/resources/ext/*" (with trailing slash and glob)
        references.add(Arguments.of("relative extLibs with glob", relativeExtLibsDir + File.separator + "*"));

        return references.stream();
    }

    /**
     * Test using WebAppContext.setExtraClassPath(String) with a reference to a glob
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("extraClasspathGlob")
    public void testExtraClasspathGlob(String description, String extraClasspathGlobReference) throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        context.setBaseResource(new PathResource(warPath));
        context.setExtraClasspath(extraClasspathGlobReference);

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected jars
        ClassLoader contextClassLoader = context.getClassLoader();
        assertThat(contextClassLoader, instanceOf(WebAppClassLoader.class));
        WebAppClassLoader webAppClassLoader = (WebAppClassLoader)contextClassLoader;
        Path extLibsDir = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibsDir = extLibsDir.toAbsolutePath();
        List<Path> expectedPaths;
        try (Stream<Path> s = Files.list(extLibsDir))
        {
            expectedPaths = s
                .filter(Files::isRegularFile)
                .filter((path) -> path.toString().endsWith(".jar"))
                .collect(Collectors.toList());
        }
        List<Path> actualPaths = new ArrayList<>();
        for (URL url : webAppClassLoader.getURLs())
        {
            actualPaths.add(Paths.get(url.toURI()));
        }
        assertThat("[" + description + "] WebAppClassLoader.urls.length", actualPaths.size(), is(expectedPaths.size()));
        for (Path expectedPath : expectedPaths)
        {
            boolean found = false;
            for (Path actualPath : actualPaths)
            {
                if (Files.isSameFile(actualPath, expectedPath))
                {
                    found = true;
                }
            }
            assertTrue(found, "[" + description + "] Not able to find expected jar in WebAppClassLoader: " + expectedPath);
        }
    }

    public static Stream<Arguments> extraClasspathDir()
    {
        List<Arguments> references = new ArrayList<>();

        Path extLibs = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibs = extLibs.toAbsolutePath();

        // Absolute reference with trailing slash
        references.add(Arguments.of(extLibs.toString() + File.separator));

        // Absolute reference without trailing slash
        references.add(Arguments.of(extLibs.toString()));

        // Establish a relative extraClassPath reference
        String relativeExtLibsDir = MavenTestingUtils.getBasePath().relativize(extLibs).toString();

        // This will be in the String form similar to "src/test/resources/ext/" (with trailing slash)
        references.add(Arguments.of(relativeExtLibsDir + File.separator));

        // This will be in the String form similar to "src/test/resources/ext/" (without trailing slash)
        references.add(Arguments.of(relativeExtLibsDir));

        return references.stream();
    }

    /**
     * Test using WebAppContext.setExtraClassPath(String) with a reference to a directory
     */
    @ParameterizedTest
    @MethodSource("extraClasspathDir")
    public void testExtraClasspathDir(String extraClassPathReference) throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        context.setBaseResource(new PathResource(warPath));

        context.setExtraClasspath(extraClassPathReference);

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected directory reference
        ClassLoader contextClassLoader = context.getClassLoader();
        assertThat(contextClassLoader, instanceOf(WebAppClassLoader.class));
        WebAppClassLoader webAppClassLoader = (WebAppClassLoader)contextClassLoader;
        URL[] urls = webAppClassLoader.getURLs();
        assertThat("URLs", urls.length, is(1));
        Path extLibs = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibs = extLibs.toAbsolutePath();
        assertThat("URL[0]", urls[0].toURI(), is(extLibs.toUri()));
    }
}
