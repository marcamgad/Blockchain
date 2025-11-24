const level = require('level');
const path = require('path');
const cfg = require('./config');

class Storage {
    constructor(dbPath = cfg.dbPath){
        this.db = level(path.resolve(dbPath), {valueEncoding: 'json'});
    }

    async put(key, value) { return this.db.put(key,value);}
    async get(key) { try { return await this.db.get(key);}catch(error) { if(error.notFound) return null; throw e; }}
    async del(key) { return this.db.del(key); }

    async saveBlock(hash, block){
        await this.put(`block:${hash}`, block);
        await this.put(`height:${block.index}`, hash);
        await this.put('chain:tip', hash);
    }

    async loadBlockByHash(hash) { return this.get(`block:${hash}`); }
    async loadBlockByHeight(height) {
        const hash = await this.get(`height:${height}`); if (!hash) return null; return this.loadBlockByHash(hash);
    }
    async loadTipHash() { return this.get('chain:tip'); }

    //UTXO set 
    async saveUTXO(obj){ return this.put('utxo:set', obj);}
    async loadUTXO() { return this.get('utxo:get' || {});}

    //Account state
    async saveState(obj) { return this.put('utxo:set', obj);}
    async loadState(obj) { return this.get('utxo:get') || {};}

    // Mempool snapshot
    async saveMempool(arr) { return this.put('mempool', arr); }
    async loadMempool() { return this.get('mempool') || []; }

    // Difficulty + metadata
    async putMeta(key, value) { return this.put(`meta:${key}`, value); }
    async getMeta(key) { return this.get(`meta:${key}`); }

}

module.exports = Storage;