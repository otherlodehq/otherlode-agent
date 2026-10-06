package com.example.target.outsidecaller.external;

/** An interface outside the scope the outside-caller tests include. */
public interface ExternalCallback {
    void onEvent(String name);
}
