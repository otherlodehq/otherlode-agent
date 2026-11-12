package com.example.target.keypairs;

// javac needs three dense cases to emit a TABLESWITCH; two give a LOOKUPSWITCH.
public class SwitchCaseAddedJavaV1 {
    public String classify(int x) {
        switch (x) {
            case 1:
                return "one";
            case 2:
                return "two";
            case 3:
                return "three";
            default:
                return "other";
        }
    }
}
