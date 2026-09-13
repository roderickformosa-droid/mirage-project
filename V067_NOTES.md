# v0.67 — Cue Capture Fix

Field fix after v0.66 failed to surface obvious moving flags/banners.

Changes:
- environmental cue detection now runs whenever stabilization tracking is usable, even if flexible objects trigger the stabilizer disturbance flag
- lower motion threshold for fabric/foliage
- less destructive morphology so thin flag edges, grass and twigs are not erased
- lower minimum moving-region area
- flag/fabric geometry threshold widened
- foliage threshold widened
- deformation energy counts as cue motion even when a flag flaps around a nearly fixed centre
- target classification remains disabled
- impact detection remains excluded

Expected field behaviour: obvious moving flags/banners should begin generating cue evidence and should no longer remain at NO RELIABLE WIND CONDITION purely because their centroid barely translates.
