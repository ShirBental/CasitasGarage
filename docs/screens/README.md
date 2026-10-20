# Home screen review shots

The two-door home screen on the simulated doors ("Simulation mode"), captured with headless Chrome at iPhone sizes, 3×,
on 2026-10-19. Standard: 393 × 852 (iPhone 15/16). Small: 375 × 667 (iPhone SE).

| | Standard | Small |
|---|---|---|
| Closed, with the sensor check after a close | `standard-1-closed.jpg` | `small-1-closed.jpg` |
| Ford opening, with the estimate | `standard-2-opening.jpg` | `small-2-opening.jpg` |
| Not closed, with the sensor check | `standard-3-not-closed.jpg` | `small-3-not-closed.jpg` |
| Can't reach home | `standard-4-offline.jpg` | `small-4-offline.jpg` |
| History sheet | `standard-5-history.jpg` | `small-5-history.jpg` |
| Account sheet | `standard-6-account.jpg` | `small-6-account.jpg` |
| Face ID setup, with the approved app icon | `standard-7-face-id-setup.jpg` | |

Watson is the approved character (`static/brand/watson-idle.webp`; `watson-excited.webp` while a door moves), only scaled. The closed and opening shots were retaken on 2026-10-19 with this art. Toyota's control
is disabled ("Toyota control not set up yet") because the server doesn't command Toyota yet.

The sign-in page shows the same icon once `/icons/*.png` is public (PR #8). On this branch alone it
can't be fetched before sign-in, so the page shows an empty space there rather than a broken image.
