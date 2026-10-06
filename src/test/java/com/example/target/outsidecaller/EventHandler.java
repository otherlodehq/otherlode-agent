package com.example.target.outsidecaller;

import com.example.target.outsidecaller.external.ExternalCallback;

/** An in-scope abstract class that implements an out-of-scope interface. */
public abstract class EventHandler extends EventBase implements ExternalCallback {}
