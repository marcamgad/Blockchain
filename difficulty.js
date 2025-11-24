const cfg = require('./config');

function adjustDifficulty(chain, currentDifficulty) {
  const N = cfg.difficultyAdjustmentInterval;
  if (chain.length <= N) return currentDifficulty;

  const last = chain[chain.length - 1];
  const prev = chain[chain.length - 1 - N];

  const actualTime = (last.timestamp - prev.timestamp) / 1000;
  const expectedTime = cfg.targetBlockTimeSec * N;
  const ratio = actualTime / expectedTime;

  const min = 0.25, max = 4;
  const adj = Math.max(min, Math.min(max, ratio));
  const newDifficulty = Math.max(1, Math.round(currentDifficulty / adj));
  return newDifficulty;
}

module.exports = { adjustDifficulty };
