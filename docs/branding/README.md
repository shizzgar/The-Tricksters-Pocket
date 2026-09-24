# ReBro Blue

The generated ninja anteater is the app's new mascot. The original transparent PNG is [rebro-mascot.png](rebro-mascot.png); the app packages a 512 px lossless WebP avatar and Android launcher assets for mdpi through xxxhdpi.

Adaptive icons have separate midnight-navy background, padded foreground and alpha-derived monochrome layers. The debug variant retains its existing debug badge. The debug package ID remains unchanged. Starting with 2.5.1-rebro.4, the separate ReBro release uses `excp.rikkahub.rebro` and a permanent owner-held signing key; see [release migration and signing](../termux-workspaces-and-release.md#release-identity-and-signing). The mascot is the avatar for newly created default ReBro profiles; saved custom profiles are preserved.

The `rebro-blue` Material palette uses navy surfaces, blue actions, steel secondary text and matching light surfaces. Error colors retain their semantic role. New installations default to this preset with wallpaper colors disabled. On an upgrade, use **Theme settings → ReBro Blue → Apply** to select it; previously saved theme choices are not overwritten. Apply also disables pure-black AMOLED surfaces so the navy palette is visible.

Text and important controls have automated contrast checks. Android tests exercise the actual settings action in both light and dark modes, load the installed adaptive launcher and decode the bundled avatar.

## Artwork provenance

Created with the built-in image generation tool (`image_gen.imagegen`). Packaging uses deterministic resizing, padding and alpha masking; app screenshots are captured separately from real Compose screens on an Android emulator.

Generation prompt:

> Use case: logo-brand. Asset type: production Android adaptive app icon foreground and assistant avatar for ReBro. Create one original, polished ninja anteater mascot emblem with a truly transparent background, square 1024 x 1024 PNG. A compact three-quarter-profile head-and-shoulders character: unmistakably long, tapered anteater snout, tiny rounded upright ears, small black nose, a clever calm focused eye, navy ninja eye mask/head wrap and two short trailing headband ties. The long snout must read as an anteater, not an elephant, wolf, mouse, bird or pig; no trunk curl, no tusks, no big elephant ears. A small high ninja collar anchors the bust. Professional bold, clean geometric vector-like illustration, broad deliberate shapes, restrained angular steel highlights, confident friendly expression, high legibility at 48 px. Palette only midnight navy #0B1427, deep blue #244C7B, clear blue #528FDC, cool pale steel #B8CADE and ice white #E4F0FF. Use the pale steel snout and light highlights to read clearly on dark navy. Center the complete silhouette within the middle 70% of the square, generous clear margins, no cropped parts. Transparent outside the mascot, no surrounding circle or shield, no app tile, no background gradient, no scene, no lettering, no text, no watermark, no weapons, no fine hair texture, no photorealism, no excessive decoration.

## NetBro and remaining app indicators

Version 2.5.1-rebro.4 packages the user-supplied green-bandanna anteater as NetBro’s avatar. Only the original satellite emoji is migrated; saved custom avatars and other assistant settings remain intact. The notification icon and optional app-style loading indicator now also use the anteater. Both build variants display the application name ReBro Agent.
