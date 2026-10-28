/** Single Tailwind build for the shell and all micro-frontends (scans every app). */
module.exports = {
  presets: [require('../../packages/ui/tailwind.preset.cjs')],
  content: ['./index.html', './src/**/*.{ts,tsx}', '../*/src/**/*.{ts,tsx}', '../../packages/ui/src/**/*.{ts,tsx}'],
};
