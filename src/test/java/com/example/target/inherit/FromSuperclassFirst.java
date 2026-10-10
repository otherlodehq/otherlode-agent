package com.example.target.inherit;

/** The superclass chain is walked before the interfaces. */
public class FromSuperclassFirst extends NearBase implements NearA, NearB {
    @Override
    public void m() {}
}
