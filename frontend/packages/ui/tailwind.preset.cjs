/** PropertyOS design tokens, shared by every micro-frontend (compiled once in the shell). */
module.exports = {
  theme: {
    extend: {
      colors: {
        federal: { 950: '#082425', 900: '#0F3D3E', 800: '#165253', 700: '#1F6869', 600: '#2D8183', 300: '#9CC3C1', 200: '#C3DCDA', 100: '#DCEBEA', 50: '#EEF6F5' },
        brick: { 800: '#6E2619', 700: '#842F20', 600: '#A23E2B', 500: '#BC5440', 200: '#EDC3B8', 100: '#F6E2DC', 50: '#FBF1EE' },
        limestone: { 50: '#FAFBFA', 100: '#F5F7F6', 200: '#E8ECEA', 300: '#D5DCD9', 400: '#BCC6C2' },
        graphite: { 950: '#121817', 900: '#1D2524', 800: '#2B3533', 700: '#3A4644', 600: '#4F5B59', 500: '#66726F', 400: '#8A9592', 300: '#B4BDBA' },
        slateblue: { 700: '#2D5579', 600: '#3B6E9C', 200: '#C2D6E9', 100: '#E1EBF4', 50: '#F0F5FA' },
        good: { 700: '#225E44', 600: '#2E7D5B', 100: '#DDF0E6', 50: '#EEF8F2' },
        warn: { 700: '#8A5A13', 600: '#B7791F', 100: '#F8EBD3', 50: '#FCF6EA' },
        crit: { 700: '#8C1D17', 600: '#B3261E', 100: '#F7DCDA', 50: '#FBEEED' },
      },
      fontFamily: {
        sans: ['"Public Sans"', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
      },
      fontSize: {
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
      },
      boxShadow: {
        panel: '0 1px 0 rgba(15,61,62,0.04), 0 1px 2px rgba(15,61,62,0.06)',
        lift: '0 12px 32px -12px rgba(8,36,37,0.28)',
        drawer: '-24px 0 48px -24px rgba(8,36,37,0.35)',
      },
      borderRadius: {
        sm: '4px',
        DEFAULT: '6px',
        md: '8px',
        lg: '12px',
      },
    },
  },
};
