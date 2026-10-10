package com.example.target.inherit;

/** The first interface in declaration order wins. */
public class FromFirstInterface implements NearA, NearB {
    @Override
    public void m() {}
}
