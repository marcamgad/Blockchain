package com.hybrid.blockchain;

import java.util.Map;

public class ContractVM {
    public void execute(String code, Map<String, Object> state){
        System.out.println("Executing contract: " + code);
    }
}
