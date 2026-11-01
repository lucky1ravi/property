/** PropertyOS design tokens, shared by every micro-frontend (compiled once in the shell). */
module.exports = {
  theme: {
    extend: {
      colors: {
        // The company's own colour scale: set as CSS variables at run time from its branding, defaults in the shell's index.css.
        federal: { 950: 'rgb(var(--federal-950) / <alpha-value>)', 900: 'rgb(var(--federal-900) / <alpha-value>)', 800: 'rgb(var(--federal-800) / <alpha-value>)', 700: 'rgb(var(--federal-700) / <alpha-value>)', 600: 'rgb(var(--federal-600) / <alpha-value>)', 300: 'rgb(var(--federal-300) / <alpha-value>)', 200: 'rgb(var(--federal-200) / <alpha-value>)', 100: 'rgb(var(--federal-100) / <alpha-value>)', 50: 'rgb(var(--federal-50) / <alpha-value>)' },
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
      keyframes: {
        // A row of a table appearing: a short rise and fade, staggered by the row's position.
        'row-in': { from: { opacity: '0', transform: 'translateY(6px)' }, to: { opacity: '1', transform: 'none' } },
        // A loading placeholder catching the light.
        shimmer: { '100%': { transform: 'translateX(100%)' } },
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
      },
      animation: {
        'row-in': 'row-in 0.28s cubic-bezier(0.22, 1, 0.36, 1) both',
        shimmer: 'shimmer 1.5s ease-in-out infinite',
        'fade-in': 'fade-in 0.2s ease-out both',
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
