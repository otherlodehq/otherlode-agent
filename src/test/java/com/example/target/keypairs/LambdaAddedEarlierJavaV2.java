package com.example.target.keypairs;

import java.util.List;

public class LambdaAddedEarlierJavaV2 {
    Runnable earlier() {
        return () -> System.out.println("earlier");
    }

    int check(List<Integer> xs) {
        return xs.stream().anyMatch(x -> x > 1) ? 1 : 0;
    }
}
