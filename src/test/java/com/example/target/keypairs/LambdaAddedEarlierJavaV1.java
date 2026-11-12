package com.example.target.keypairs;

import java.util.List;

public class LambdaAddedEarlierJavaV1 {
    int check(List<Integer> xs) {
        return xs.stream().anyMatch(x -> x > 1) ? 1 : 0;
    }
}
