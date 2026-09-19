import { defineConfig } from 'vite';

/**
 * A Tizen widget is a folder of files opened from `index.html` over the `file:`-like `app://`
 * scheme, not served from a web root. Absolute paths would resolve against the device root and
 * find nothing, so everything is emitted relative.
 *
 * hls.js is deliberately left as its own chunk rather than inlined. It exists only so the app can
 * be developed in a browser, which cannot decode what Samsung's AVPlay handles natively - folding
 * it into the main bundle would have a television parse a third of a megabyte of code, on a slow
 * engine, before the first frame, to support a player it will never construct.
 */
export default defineConfig({
  base: './',
  build: {
    target: 'es2017',
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: true,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: 'assets/[name][extname]',
      },
    },
  },
  server: {
    host: true,
    port: 5178,
  },
});
