package com.example.servlet.fixture;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Declares {@code GET} and a {@code doPatch} that only a Servlet 6.1 {@code HttpServlet} calls. It is
 * Java so it compiles against an API that declares {@code doPatch} and one that does not.
 */
public class PatchServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().write("get");
    }

    public void doPatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().write("patch");
    }
}
