# Inter, for the growth chart

`Inter-Regular.ttf` and `Inter-Bold.ttf` are static instances (wght 400 and 700) of the
self-hosted variable font `frontend/fonts/Inter-latin.woff2`, produced on 2026-10-27 with
fontTools (`varLib.instancer`). They exist so `GrowthChartRenderer` can draw the chart's labels
in the site's face on a JDK that has no fonts installed (Railway's image); nothing is ever
fetched at runtime. The renderer falls back to the JDK's logical SansSerif when the files are
missing. Licensed under the SIL Open Font License 1.1 (`OFL.txt`).

# Inter Medium and Montserrat, for the share images

Added 2026-11-14 for `ShareImageRenderer` (the link preview image and the story image of
"Share my Freedom Date"), which also uses the two Inter files above. Unmodified static TTFs
from the upstream projects, nothing fetched at runtime:

- `Inter-Medium.ttf`: `extras/ttf/Inter-Medium.ttf` from the Inter 4.1 release archive
  (https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip).
- `Montserrat-Black.ttf`, `Montserrat-BlackItalic.ttf`: `fonts/ttf/` of
  https://github.com/JulietaUla/Montserrat (master, fetched 2026-11-14). The italic is the
  face of the "FRED" wordmark, as in the landing page's nav.

Both families are licensed under the SIL Open Font License 1.1 (`OFL.txt` carries both
copyright notices). `ShareImageRendererTest` fails if any of these files is missing.
