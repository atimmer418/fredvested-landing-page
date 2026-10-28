# Inter, for the growth chart

`Inter-Regular.ttf` and `Inter-Bold.ttf` are static instances (wght 400 and 700) of the
self-hosted variable font `frontend/fonts/Inter-latin.woff2`, produced on 2026-10-27 with
fontTools (`varLib.instancer`). They exist so `GrowthChartRenderer` can draw the chart's labels
in the site's face on a JDK that has no fonts installed (Railway's image); nothing is ever
fetched at runtime. The renderer falls back to the JDK's logical SansSerif when the files are
missing. Licensed under the SIL Open Font License 1.1 (`OFL.txt`).
