// vm.js
/*
A tiny sandbox for contract execution using Node's vm module.
In a production chain you'd use WASM or a fully sandboxed environment.
This example demonstrates how contract calls might be executed and how to charge gas.
*/

const vm = require('vm');

class SimpleVM {
  constructor({ state }) {
    this.state = state; // reference to AccountState or similar
  }

  // Executes contract code (string) with context; returns result and gas used
  run(contractCode, method, args = [], gasLimit = 100000) {
    // extremely simple gas model: 1 step = 1 unit; we won't actually meter JS ops here
    const sandbox = { state: this.state, console, result: null, args };
    const script = new vm.Script(`${contractCode}\nresult = (typeof ${method} === 'function') ? ${method}(...args) : null;`);
    const context = vm.createContext(sandbox, { name: 'contract-sandbox' });
    script.runInContext(context, { timeout: 1000 }); // timeout ms
    return { result: sandbox.result, gasUsed: Math.min(gasLimit, 100) }; // simplistic
  }
}

module.exports = SimpleVM;
