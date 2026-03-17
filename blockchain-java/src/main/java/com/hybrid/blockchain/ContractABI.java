package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a smart contract's Application Binary Interface (ABI).
 * An ABI describes the callable functions of a contract, including their
 * parameter types and return types, enabling clients to encode calls and
 * decode results without knowing the bytecode internals.
 *
 * <p>The ABI is stored as JSON alongside the contract bytecode in {@link AccountState.Account}.
 */
public class ContractABI {

    /**
     * Describes a single callable function in the contract's ABI.
     */
    public static class FunctionDef {
        private final String name;
        private final List<String> parameterTypes;
        private final String returnType;

        /**
         * Constructs a FunctionDef.
         *
         * @param name           the function name
         * @param parameterTypes list of parameter type strings (e.g., "long", "address")
         * @param returnType     return type string (e.g., "long", "void")
         */
        @JsonCreator
        public FunctionDef(
                @JsonProperty("name") String name,
                @JsonProperty("parameterTypes") List<String> parameterTypes,
                @JsonProperty("returnType") String returnType) {
            this.name = name;
            this.parameterTypes = parameterTypes != null ? new ArrayList<>(parameterTypes) : new ArrayList<>();
            this.returnType = returnType;
        }

        /** @return the function name */
        public String getName() { return name; }

        /** @return ordered list of parameter types */
        public List<String> getParameterTypes() { return parameterTypes; }

        /** @return the return type */
        public String getReturnType() { return returnType; }
    }

    private final String contractAddress;
    private final List<FunctionDef> functions;

    /**
     * Constructs a ContractABI.
     *
     * @param contractAddress the contract address this ABI belongs to
     * @param functions       list of function definitions
     */
    @JsonCreator
    public ContractABI(
            @JsonProperty("contractAddress") String contractAddress,
            @JsonProperty("functions") List<FunctionDef> functions) {
        this.contractAddress = contractAddress;
        this.functions = functions != null ? new ArrayList<>(functions) : new ArrayList<>();
    }

    /** @return the contract address */
    public String getContractAddress() { return contractAddress; }

    /** @return list of function definitions */
    public List<FunctionDef> getFunctions() { return functions; }

    /**
     * Looks up a function definition by name.
     *
     * @param name the function name to look up
     * @return the FunctionDef, or null if not found
     */
    public FunctionDef getFunction(String name) {
        return functions.stream().filter(f -> name.equals(f.getName())).findFirst().orElse(null);
    }
}
