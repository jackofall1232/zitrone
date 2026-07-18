// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

"use client";

import { useState } from "react";
import { motion, useReducedMotion } from "framer-motion";
import { LemonSlice } from "@/components/LemonSlice";

/**
 * One-time hero entrance: a droplet falls, splashes, a whole lemon springs up
 * from the splash, a quick cross-cut reveals the slice, and the wheel spins in
 * and settles on the exact static LemonSlice block the hero always had (same
 * classes, same ambient 120 s rotation). Plays once on load, never loops.
 * `prefers-reduced-motion` skips straight to the settled end-state.
 *
 * Timeline (~2.7 s): drop 0–0.6 · splash 0.55–1.0 · lemon 0.9–1.75 ·
 * cut 1.6–1.95 · wheel spin 1.78–2.7 · settled.
 */

const LEMON = "#F5E642";
const RIND = "#D4C200";

/** Teardrop pointing down — tip up, round belly. Drawn around (50, 44). */
const DROPLET_PATH =
  "M 50 30 C 47 36, 43.5 40.5, 43.5 45 A 6.5 6.5 0 1 0 56.5 45 C 56.5 40.5, 53 36, 50 30 Z";

/** Outward splash droplets: [angle°, distance]. */
const SPLASH_DROPS: Array<[number, number]> = [
  [200, 30],
  [245, 36],
  [285, 33],
  [335, 30],
  [160, 34],
  [20, 28],
];

/** viewBox-unit transforms about the slice center. */
const CENTERED = { transformBox: "view-box", transformOrigin: "50% 50%" } as const;

/** The hero mark exactly as it has always been — the animation's end state. */
function SettledWheel() {
  return (
    <motion.div
      aria-hidden
      className="relative mb-10 drop-shadow-[0_0_60px_rgba(245,230,66,0.25)]"
      animate={{ rotate: 360 }}
      transition={{ duration: 120, ease: "linear", repeat: Infinity }}
    >
      <LemonSlice size={220} label="" className="h-[160px] w-[160px] sm:h-[220px] sm:w-[220px]" />
    </motion.div>
  );
}

export function HeroLemon() {
  const reducedMotion = useReducedMotion();
  const [settled, setSettled] = useState(false);

  if (reducedMotion || settled) {
    return <SettledWheel />;
  }

  return (
    <div
      aria-hidden
      className="relative mb-10 h-[160px] w-[160px] drop-shadow-[0_0_60px_rgba(245,230,66,0.25)] sm:h-[220px] sm:w-[220px]"
    >
      <motion.svg
        viewBox="0 0 100 100"
        className="absolute inset-0 h-full w-full"
        style={{ overflow: "visible" }}
      >
        {/* 1 — droplet: gravity fall from above the container, slight stretch. */}
        <motion.path
          d={DROPLET_PATH}
          fill={LEMON}
          initial={{ y: -110, opacity: 1 }}
          animate={{ y: [-110, 0, 0], opacity: [1, 1, 0] }}
          transition={{ duration: 0.62, times: [0, 0.85, 1], ease: "easeIn" }}
        />

        {/* 2 — splash: an expanding ripple ring… */}
        <motion.g style={CENTERED} initial={{ scale: 0.15, opacity: 0 }}
          animate={{ scale: [0.15, 1, 1.35], opacity: [0, 0.9, 0] }}
          transition={{ delay: 0.52, duration: 0.45, times: [0, 0.55, 1], ease: "easeOut" }}
        >
          <circle cx="50" cy="50" r="26" fill="none" stroke={LEMON} strokeWidth="2.5" />
        </motion.g>
        {/* …and beads thrown outward. */}
        {SPLASH_DROPS.map(([angle, dist], i) => {
          const rad = ((angle - 90) * Math.PI) / 180;
          return (
            <motion.circle
              key={i}
              cx="50"
              cy="50"
              r="2.4"
              fill={LEMON}
              initial={{ x: 0, y: 0, opacity: 0 }}
              animate={{
                x: [0, Math.cos(rad) * dist],
                y: [0, Math.sin(rad) * dist - 6],
                opacity: [0.95, 0],
                scale: [1, 0.4],
              }}
              transition={{ delay: 0.55, duration: 0.42, ease: "easeOut" }}
            />
          );
        })}

        {/* 3 — a whole lemon springs up out of the splash, then yields to the
            cut (its fade-out is timed to the knife stroke below). */}
        <motion.g
          style={CENTERED}
          initial={{ scale: 0, opacity: 0 }}
          animate={{ scale: [0, 1.12, 1, 1, 1.06], opacity: [0, 1, 1, 1, 0] }}
          transition={{ delay: 0.9, duration: 1.0, times: [0, 0.4, 0.62, 0.82, 1], ease: "easeOut" }}
        >
          <g transform="rotate(-18 50 50)">
            <ellipse cx="50" cy="50" rx="31" ry="23" fill={LEMON} stroke={RIND} strokeWidth="3" />
            <ellipse cx="18.5" cy="50" rx="5.5" ry="4.5" fill={LEMON} stroke={RIND} strokeWidth="3" />
            <ellipse cx="81.5" cy="50" rx="5.5" ry="4.5" fill={LEMON} stroke={RIND} strokeWidth="3" />
            {/* soft sheen so the fruit reads as a solid, not a disc */}
            <ellipse cx="41" cy="42" rx="10" ry="5.5" fill="#FBF3A0" opacity="0.55" transform="rotate(-14 41 42)" />
          </g>
        </motion.g>

        {/* 4 — cross-cut: one quick vertical knife stroke across the fruit. */}
        <motion.line
          x1="50"
          y1="6"
          x2="50"
          y2="94"
          stroke="#FFFDF0"
          strokeWidth="2.5"
          strokeLinecap="round"
          initial={{ pathLength: 0, opacity: 0 }}
          animate={{ pathLength: [0, 1, 1], opacity: [0.95, 0.95, 0] }}
          transition={{ delay: 1.6, duration: 0.35, times: [0, 0.55, 1], ease: "easeIn" }}
        />
      </motion.svg>

      {/* 5 — the cut face: the slice wheel spins in and decelerates onto the
          exact pose the settled mark holds. */}
      <motion.div
        className="absolute inset-0"
        initial={{ opacity: 0, rotate: -520, scale: 0.72 }}
        animate={{ opacity: 1, rotate: 0, scale: 1 }}
        transition={{
          delay: 1.78,
          duration: 0.92,
          ease: [0.16, 1, 0.3, 1],
          opacity: { delay: 1.78, duration: 0.18 },
        }}
        onAnimationComplete={() => setSettled(true)}
      >
        <LemonSlice size={220} label="" className="h-full w-full" />
      </motion.div>
    </div>
  );
}
