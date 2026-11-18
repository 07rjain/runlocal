(() => {
  const canvas = document.getElementById('dot-product');
  if (!(canvas instanceof HTMLCanvasElement)) return;
  const context = canvas.getContext('2d');
  if (!context) return;

  // A fixed seed gives the same signed weights on every visit.
  let seed = 1973;
  function nextWeight(row, column) {
    seed = (Math.imul(1664525, seed) + 1013904223) >>> 0;
    const noise = seed / 0x100000000 - 0.5;
    return Math.round(1.45 * Math.sin((column - row * 1.65) * 0.66) + 1.7 * noise);
  }

  function line(x1, y1, x2, y2) {
    context.beginPath();
    context.moveTo(x1, y1);
    context.lineTo(x2, y2);
    context.stroke();
  }

  function matrixMark(x, y, weight) {
    if (weight === 0) return;
    const size = 2.4 + Math.abs(weight) * 1.13;
    if (weight > 0) {
      context.fillStyle = '#46654f';
      context.fillRect(x - size / 2, y - size / 2, size, size);
    } else {
      context.strokeStyle = '#9b7a4d';
      context.strokeRect(x - size / 2, y - size / 2, size, size);
    }
  }

  const input = [1, -1, 2, 0, -1, 2, 1, -1, 0, 1, -2, 1];
  const xFor = column => 24 + column * 13;
  const rows = 7;
  const outputX = 217;
  const scale = canvas.width / 262;
  context.scale(scale, scale);
  context.lineWidth = 0.85;
  context.fillStyle = '#eef2e9';
  context.fillRect(0, 0, 262, 112);

  // Calibrated guides frame the matrix and the common zero of every result.
  context.strokeStyle = '#cad6c8';
  line(12, 24.5, 250, 24.5);
  line(12, 99.5, 250, 99.5);
  line(177.5, 24.5, 177.5, 99.5);
  line(outputX + 0.5, 28, outputX + 0.5, 96);
  input.forEach((value, column) => matrixMark(xFor(column), 15, value));

  for (let row = 0; row < rows; row++) {
    const y = 34 + 9.4 * row;
    let sum = 0;
    input.forEach((value, column) => {
      const weight = nextWeight(row, column);
      matrixMark(xFor(column), y, weight);
      sum += value * weight;
    });
    const length = sum * 6;
    context.fillStyle = sum < 0 ? '#9b7a4d' : '#46654f';
    context.fillRect(outputX + Math.min(0, length), y - 1.75, Math.max(1, Math.abs(length)), 3.5);
  }

  canvas.hidden = false;
})();
