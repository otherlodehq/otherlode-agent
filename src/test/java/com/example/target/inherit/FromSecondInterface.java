package com.example.target.inherit;

/** Declaration order, not name, decides. */
public class FromSecondInterface implements NearB, NearA {
    @Override
    public void m() {}
}
