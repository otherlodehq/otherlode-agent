package dev.otherlode.instrumentation.endpoints.vertxweb

import kotlin.test.Test
import kotlin.test.assertEquals

class PathTemplatesTest {
    @Test
    fun `a colon parameter becomes a brace parameter and a type suffix is dropped`() {
        assertEquals("/users/{id}", toTemplate("/users/:id"))
        assertEquals("/orgs/{org}/members/{member}", toTemplate("/orgs/:org/members/:member"))
        assertEquals("/users/{id}", toTemplate("/users/:id::integer"))
        assertEquals("/flights/{from}-{to}", toTemplate("/flights/:from-:to"))
        assertEquals("/files/{name}/", toTemplate("/files/:name/"))
        assertEquals("/plain/path", toTemplate("/plain/path"))
        assertEquals("/a:", toTemplate("/a:"), "a colon that starts no name stays")
    }

    @Test
    fun `the extended name pattern reads dashes and dollars as part of a name`() {
        val property = "io.vertx.web.route.param.extended-pattern"
        val before = System.getProperty(property)
        try {
            System.setProperty(property, "true")
            assertEquals("/a/{my-id}", toTemplate("/a/:my-id"))
            assertEquals("/a/{\$id}", toTemplate("/a/:\$id"))
        } finally {
            if (before == null) System.clearProperty(property) else System.setProperty(property, before)
        }
        assertEquals("/a/{my}-id", toTemplate("/a/:my-id"))
    }

    @Test
    fun `a mount point is composed the way Vert_x composes it`() {
        assertEquals("/api/", composeMountPoint(null, "/api/"))
        assertEquals("/api/v1/", composeMountPoint("/api/", "/v1/"))
        assertEquals("/api/v1", composeMountPoint("/api", "/v1"))
        assertEquals("/api/", composeMountPoint("/api/", "/"), "a sub-router mounted on / keeps the parent's mount")
        assertEquals("/x/", composeMountPoint("/", "/x/"), "a parent mounted on / defers to the child's")
        assertEquals("/", composeMountPoint(null, "/"))
    }

    @Test
    fun `a path route is joined to its mount prefix`() {
        assertEquals("/users/{id}", pathTemplate(null, "/users/:id", exact = true))
        assertEquals("/api/users", pathTemplate("/api/", "/users", exact = true))
        assertEquals("/api/users", pathTemplate("/api", "/users", exact = true))
        assertEquals("/api/", pathTemplate("/api/", "/", exact = true))
        assertEquals("/files/*", pathTemplate(null, "/files/", exact = false))
        assertEquals("/api/files/*", pathTemplate("/api/", "/files/", exact = false))
        assertEquals("/files*", pathTemplate(null, "/files", exact = false))
        assertEquals("/orgs/{org}/members", pathTemplate("/orgs/:org/", "/members", exact = true))
    }

    @Test
    fun `a route with no path, or a wildcard on the root, matches everything under its mount`() {
        assertEquals("/*", pathTemplate(null, null, exact = true))
        assertEquals("/*", pathTemplate(null, "/", exact = false))
        assertEquals("/api/*", pathTemplate("/api/", null, exact = true))
        assertEquals("/api/*", pathTemplate("/api", "/", exact = false))
        assertEquals("/*", pathTemplate("/", null, exact = true))
        assertEquals("/orgs/{org}/*", pathTemplate("/orgs/:org/", null, exact = true))
    }

    @Test
    fun `a regex route keeps its source after its mount prefix`() {
        assertEquals("/items/(\\d+)", regexTemplate(null, "/items/(\\d+)"))
        assertEquals("/api/items/(\\d+)", regexTemplate("/api/", "/items/(\\d+)"))
        assertEquals("/orgs/{org}/items/(\\d+)", regexTemplate("/orgs/:org/", "/items/(\\d+)"))
        assertEquals(".*", regexTemplate(null, ".*"))
        assertEquals("/items/:id", regexTemplate(null, "/items/:id"), "the source is not read for parameters")
    }
}
