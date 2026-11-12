package com.example.target;

public class AnonymousClassTarget {

    private void helper() {
    }

    public Runnable makeAnonymousRunnable() {
        return new Runnable() {
            public void run() {
                helper();
            }
        };
    }

    public Runnable makeLocalClassRunnable() {
        class LocalRunnable implements Runnable {
            public void run() {
                helper();
            }
        }
        return new LocalRunnable();
    }
}
