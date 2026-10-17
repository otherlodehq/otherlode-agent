package dev.otherlode.registry

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTemplateNormalizerTest {
    @Test
    fun `a plain path gets a leading slash`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("orders"))
    }

    @Test
    fun `an empty input normalises to the root template`() {
        assertEquals("/", RouteTemplateNormalizer.normalize(""))
    }

    @Test
    fun `a blank input normalises to the root template`() {
        assertEquals("/", RouteTemplateNormalizer.normalize("   "))
    }

    @Test
    fun `the root path stays the root template`() {
        assertEquals("/", RouteTemplateNormalizer.normalize("/"))
    }

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("/orders/"))
    }

    @Test
    fun `runs of slashes collapse to one`() {
        assertEquals("/orders/1", RouteTemplateNormalizer.normalize("//orders//1//"))
    }

    @Test
    fun `a Spring path parameter keeps its name`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{id}"))
    }

    @Test
    fun `a Spring tail wildcard collapses to a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/**"))
    }

    @Test
    fun `a Spring 6 captures-the-rest parameter collapses to a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/{*path}"))
    }

    @Test
    fun `a single star segment stays a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/*"))
    }

    @Test
    fun `a JAX-RS regex constraint written with a space is dropped`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("""/orders/{id: \d+}"""))
    }

    @Test
    fun `a JAX-RS regex constraint written as a character class is dropped`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{id:[0-9]+}"))
    }

    @Test
    fun `a JAX-RS regex constraint written as any-character is dropped`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{id:.+}"))
    }

    @Test
    fun `whitespace around a path parameter name is trimmed`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{ id : \\d+ }"))
    }

    @Test
    fun `a Ktor tail wildcard collapses to a single star segment`() {
        assertEquals("/*", RouteTemplateNormalizer.normalize("/{...}"))
    }

    @Test
    fun `a Ktor named tail parameter collapses to a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/{path...}"))
    }

    @Test
    fun `a Ktor optional parameter keeps its question mark`() {
        assertEquals("/{id?}", RouteTemplateNormalizer.normalize("/{id?}"))
    }

    @Test
    fun `a Micronaut optional parameter becomes the optional marker`() {
        assertEquals("/books/{id?}", RouteTemplateNormalizer.normalize("/books/{id:?}"))
        assertEquals("/books/{id?}", RouteTemplateNormalizer.normalize("/books/{ id : ? }"))
    }

    @Test
    fun `a Micronaut length or regex constraint is dropped`() {
        assertEquals("/books/{id}", RouteTemplateNormalizer.normalize("/books/{id:2}"))
        assertEquals("/books/{id}", RouteTemplateNormalizer.normalize("/books/{id:\\d+}"))
        assertEquals("/a/{x}", RouteTemplateNormalizer.normalize("/a/{x:.*}"))
    }

    @Test
    fun `a Micronaut optional segment becomes a segment of its own`() {
        assertEquals("/books/{id?}", RouteTemplateNormalizer.normalize("/books{/id}"))
        assertEquals("/books/{id?}", RouteTemplateNormalizer.normalize("/books{/id:2}"))
        assertEquals("/{id?}", RouteTemplateNormalizer.normalize("/{/id}"))
    }

    @Test
    fun `a Micronaut optional segment list is kept as written`() {
        assertEquals("/books{/a,b}", RouteTemplateNormalizer.normalize("/books{/a,b}"))
    }

    @Test
    fun `a whole-segment reserved expansion collapses to a star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/{+path}"))
    }

    @Test
    fun `a reserved expansion inside a segment is kept as written`() {
        assertEquals("/files/a{+x}", RouteTemplateNormalizer.normalize("/files/a{+x}"))
    }

    @Test
    fun `a Javalin whole-segment angle parameter collapses to a star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/<path>"))
        assertEquals("/x/*/meta", RouteTemplateNormalizer.normalize("/x/<p>/meta"))
    }

    @Test
    fun `a Javalin angle parameter sharing its segment with text is kept as written`() {
        assertEquals("/x/a<p>", RouteTemplateNormalizer.normalize("/x/a<p>"))
        assertEquals("/x/<p>.txt", RouteTemplateNormalizer.normalize("/x/<p>.txt"))
        assertEquals("/x/<a<b>", RouteTemplateNormalizer.normalize("/x/<a<b>"))
        assertEquals("/x/< >", RouteTemplateNormalizer.normalize("/x/< >"))
        assertEquals("/x/<>", RouteTemplateNormalizer.normalize("/x/<>"))
        assertEquals("/x/<{y}>", RouteTemplateNormalizer.normalize("/x/<{y}>"))
    }

    @Test
    fun `a Micronaut form whose meaning would be a guess is kept as written`() {
        assertEquals("/a/{.x}", RouteTemplateNormalizer.normalize("/a/{.x}"))
        assertEquals("/a/{;x}", RouteTemplateNormalizer.normalize("/a/{;x}"))
        assertEquals("/a/{x*}", RouteTemplateNormalizer.normalize("/a/{x*}"))
        assertEquals("/a/{a,b}", RouteTemplateNormalizer.normalize("/a/{a,b}"))
        assertEquals("/a/{?q}", RouteTemplateNormalizer.normalize("/a/{?q}"))
        assertEquals("/a/{&q}", RouteTemplateNormalizer.normalize("/a/{&q}"))
        assertEquals("/a/{#f}", RouteTemplateNormalizer.normalize("/a/{#f}"))
        assertEquals("/a/{+.x}", RouteTemplateNormalizer.normalize("/a/{+.x}"))
        assertEquals("/a{/.x}", RouteTemplateNormalizer.normalize("/a{/.x}"))
    }

    @Test
    fun `a star embedded inside a segment is left as written`() {
        assertEquals("/files/*.txt", RouteTemplateNormalizer.normalize("/files/*.txt"))
    }

    @Test
    fun `every parameter in a segment keeps its name and the text around it`() {
        assertEquals("/files/{id}.{ext}", RouteTemplateNormalizer.normalize("/files/{id:\\d+}.{ext}"))
        assertEquals(
            "/lib/{name}-{version}{ext}",
            RouteTemplateNormalizer.normalize("/lib/{name:[a-z-]+}-{version:\\d\\.\\d}{ext:\\.[a-z]+}"),
        )
    }

    @Test
    fun `a regex holding a slash stays inside its parameter`() {
        assertEquals("/files/{path}", RouteTemplateNormalizer.normalize("/files/{path: [^/]+}"))
        assertEquals("/a/{p}/b", RouteTemplateNormalizer.normalize("/a/{p:.+/x}/b"))
    }

    @Test
    fun `a regex holding braces stays inside its parameter`() {
        assertEquals("/zip/{code}", RouteTemplateNormalizer.normalize("/zip/{code:\\d{5}}"))
    }

    @Test
    fun `a parameter with literal text before it keeps both`() {
        assertEquals("/v{version}/items", RouteTemplateNormalizer.normalize("/v{version:\\d+}/items"))
    }

    @Test
    fun `an unclosed brace is kept as written`() {
        assertEquals("/a/{oops/b", RouteTemplateNormalizer.normalize("/a/{oops/b"))
    }

    @Test
    fun `a context path is prefixed with its own leading slash guaranteed`() {
        assertEquals("/app/orders", RouteTemplateNormalizer.normalize("/orders/", "/app/"))
    }

    @Test
    fun `a null context path is not prefixed`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("/orders", null))
    }

    @Test
    fun `a blank context path is not prefixed`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("/orders", "   "))
    }

    @Test
    fun `normalizeVerb uppercases and trims`() {
        assertEquals("GET", RouteTemplateNormalizer.normalizeVerb(" get "))
    }

    @Test
    fun `normalizeVerb treats a null verb as unconstrained`() {
        assertEquals("*", RouteTemplateNormalizer.normalizeVerb(null))
    }

    @Test
    fun `normalizeVerb treats a blank verb as unconstrained`() {
        assertEquals("*", RouteTemplateNormalizer.normalizeVerb("  "))
    }

    @Test
    fun `normalizeVerb treats a literal star as unconstrained`() {
        assertEquals("*", RouteTemplateNormalizer.normalizeVerb("*"))
    }
}
