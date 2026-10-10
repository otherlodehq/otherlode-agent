package com.example.target.inherit;

/** An unannotated interface that extends an activity interface; its own methods are not activities. */
public interface ActMixedApi extends ActApi {
    String extra();
}
