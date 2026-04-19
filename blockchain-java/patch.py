import re

with open(r'd:\Blockchain\blockchain-java\src\main\java\com\hybrid\blockchain\Blockchain.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Fix addTransaction exception
content = content.replace(
    'throw new Exception("Invalid nonce: " + tx.getNonce() + " <= current ledger nonce " + currentNonce);',
    'throw new IllegalArgumentException("Invalid nonce: " + tx.getNonce() + " <= current ledger nonce " + currentNonce);'
)

# 2. Add skipNonce param
content = content.replace(
    'public void validateTransaction(Transaction tx) throws Exception {',
    'public void validateTransaction(Transaction tx) throws Exception { validateTransaction(tx, false); }\n    public void validateTransaction(Transaction tx, boolean skipNonce) throws Exception {'
)

# 3. Fix all nonce checks in validateTransaction
# The pattern is usually:
# long somethingNonce = state.getNonce(tx.getFrom()) + 1;
# if (tx.getNonce() != somethingNonce)
#     throw new Exception("Invalid nonce..."); 
# We need to turn them into IllegalArgumentException and wrap in if (!skipNonce) { ... }
# Let's match:
# \s*long \w+ = state\.getNonce\(tx\.getFrom\(\)\) \+ 1;\s*if\s*\(tx\.getNonce\(\)\s*!=\s*\w+\)\s*throw new.*?;\n
pattern = re.compile(
    r'(\s*long (\w+) = state\.getNonce\(tx\.getFrom\(\)\) \+ 1;\s*'
    r'if\s*\(tx\.getNonce\(\)\s*!=\s*\2\)\s*'
    r'throw new .*?;\s*)'
)

def replacer(match):
    original = match.group(0)
    var_name = match.group(2)
    new_throw = f'throw new IllegalArgumentException("Invalid nonce: expected " + {var_name} + " got " + tx.getNonce());'
    # Just replace the throw part:
    replaced_throw = re.sub(r'throw new .*?;',wewewew_throw, original) -> whoops, typo here
    pass

# A simpler regex that matches exactly the 2 statements:
def replace_nonce(m):
    orig = m.group(0)
    var = m.group(1)
    return f'\n                    if (!skipNonce) {{\n                        long {var} = state.getNonce(tx.getFrom()) + 1;\n                        if (tx.getNonce() != {var}) throw new IllegalArgumentException("Invalid nonce: expected " + {var} + " got " + tx.getNonce());\n                    }}\n'

content = re.sub(r'\s*long (\w+) = state\.getNonce\(tx\.getFrom\(\)\) \+ 1;\s*if\s*\(tx\.getNonce\(\)\s*!=\s*\1\)\s*throw new.*?;', replace_nonce, content)


# 4. Fix applyBlockInternal
# sorting and passing skipNonce=true
apply_block_orig = '''        for (Transaction tx : block.getTransactions()) {
            validateTransaction(tx);
        }'''
        
apply_block_new = '''        List<Transaction> sortedTxs = new java.util.ArrayList<>(block.getTransactions());
        sortedTxs.sort(java.util.Comparator
                .comparingInt((Transaction tx) -> tx.getType() == Transaction.Type.MINT ? 0 : 1)
                .thenComparing(tx -> tx.getFrom() == null ? "" : tx.getFrom())
                .thenComparingLong(Transaction::getNonce));

        java.util.Map<String, Long> expectedNonces = new java.util.LinkedHashMap<>();
        for (Transaction tx : sortedTxs) {
            if (tx.getFrom() != null) {
                long base = expectedNonces.computeIfAbsent(tx.getFrom(),
                        addr -> state.getNonce(addr) + 1);
                if (tx.getNonce() != base) {
                    throw new IllegalArgumentException("Invalid nonce for " + tx.getFrom() + ": expected " + base + " got " + tx.getNonce());
                }
                expectedNonces.put(tx.getFrom(), base + 1);
            }
        }
        for (Transaction tx : sortedTxs) {
            validateTransaction(tx, true);
        }'''

content = content.replace(apply_block_orig, apply_block_new)

# and apply loop:
content = content.replace(
'''        // Apply transactions and create receipts
        for (Transaction tx : block.getTransactions()) {''',
'''        // Apply transactions and create receipts
        for (Transaction tx : sortedTxs) {'''
)

with open(r'd:\Blockchain\blockchain-java\src\main\java\com\hybrid\blockchain\Blockchain.java', 'w', encoding='utf-8') as f:
    f.write(content)
