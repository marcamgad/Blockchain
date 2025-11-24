const Libp2p = require('libp2p');
const TCP = require('@libp2p/tcp');
const Websockets = require('@libp2p/websockets');
const { NOISE } = require('@chainsafe/libp2p-noise');
const Mplex = require('@libp2p/mplex');
const Gossipsub = require('@libp2p/gossipsub').Gossipsub;
const mdns = require('@libp2p/mdns');
const bootstrap = require('@libp2p/bootstrap');
const cfg = require('./config');

class P2PNode {
  constructor({ onTx, onBlock, bootstrapList = cfg.bootstrapPeers } = {}) {
    this.node = null;
    this.onTx = onTx;
    this.onBlock = onBlock;
    this.bootstrapList = bootstrapList;
  }

  async start() {
    this.node = await Libp2p.create({
      transports: [new TCP(), new Websockets()],
      connectionEncryption: [NOISE],
      streamMuxers: [Mplex()],
      peerDiscovery: [mdns(), bootstrap({ list: this.bootstrapList })],
      pubsub: new Gossipsub()
    });

    this.node.addEventListener('peer:discovery', evt => {
      this.node.dial(evt.detail.id).catch(() => {});
    });

    await this.node.start();

    await this.node.pubsub.subscribe(cfg.gossipsubTopicTx);
    await this.node.pubsub.subscribe(cfg.gossipsubTopicBlock);

    this.node.pubsub.addEventListener('message', evt => {
      const topic = evt.detail.topic;
      try {
        const data = JSON.parse(new TextDecoder().decode(evt.detail.data));
        if (topic === cfg.gossipsubTopicTx && this.onTx) this.onTx(data, evt.detail.from);
        if (topic === cfg.gossipsubTopicBlock && this.onBlock) this.onBlock(data, evt.detail.from);
      } catch (e) {
        console.warn('Failed parse pubsub message', e.message);
      }
    });

    console.log('libp2p started with id', this.node.peerId.toString());
  }

  async stop() { if (this.node) await this.node.stop(); }
  publishTx(tx) { return this.node.pubsub.publish(cfg.gossipsubTopicTx, Buffer.from(JSON.stringify(tx))); }
  publishBlock(block) { return this.node.pubsub.publish(cfg.gossipsubTopicBlock, Buffer.from(JSON.stringify(block))); }
}

module.exports = P2PNode;
