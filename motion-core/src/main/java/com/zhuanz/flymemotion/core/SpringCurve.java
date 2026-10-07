package com.zhuanz.flymemotion.core;

/** Closed-form unit-mass spring. Sampling is independent of display frame rate. */
public final class SpringCurve {
    private final double omega, damping, velocity;
    public final long durationMillis;

    public SpringCurve(float stiffness, float damping, float normalizedVelocity, float distancePixels) {
        this.omega = Math.sqrt(MotionConfig.bounded(stiffness, 50, 800, 200));
        this.damping = MotionConfig.bounded(damping, .3f, 1.5f, .95f);
        this.velocity = MotionConfig.bounded(normalizedVelocity, -80, 80, 0);
        double epsilon = Math.min(.001, .5 / Math.max(1, Math.abs(distancePixels)));
        int quiet = 0; long duration = 2000;
        for (int frame = 1; frame <= 480; frame++) {
            double t = frame / 240.0;
            if (Math.abs(valueAtSeconds(t) - 1) < epsilon && Math.abs(velocityAtSeconds(t)) < epsilon * 20) {
                if (++quiet >= 12) { duration = Math.round(t * 1000); break; }
            } else quiet = 0;
        }
        durationMillis = Math.max(120, duration);
    }

    public double valueAtSeconds(double t) { return 1 + state(Math.max(0, t), false); }
    public double velocityAtSeconds(double t) { return state(Math.max(0, t), true); }

    public float interpolate(float fraction) {
        if (fraction <= 0) return 0;
        if (fraction >= 1) return 1;
        // Preserve spring overshoot. Only MotionFrame.nativeHeight is bounded for the OEM panel.
        return (float) valueAtSeconds(fraction * durationMillis / 1000.0);
    }

    private double state(double t, boolean derivative) {
        if (Math.abs(damping - 1) < 1e-5) {
            double b = velocity - omega;
            double e = Math.exp(-omega * t);
            return derivative ? e * (b + omega - omega * b * t) : e * (-1 + b * t);
        }
        if (damping < 1) {
            double a = damping * omega, w = omega * Math.sqrt(1 - damping * damping);
            double b = (velocity - a) / w;
            double c = Math.cos(w * t), s = Math.sin(w * t), e = Math.exp(-a * t);
            return derivative ? e * (a * c - a * b * s + w * s + b * w * c) : e * (-c + b * s);
        }
        double root = Math.sqrt(damping * damping - 1);
        double r1 = -omega * (damping - root), r2 = -omega * (damping + root);
        double c1 = (velocity + r2) / (r1 - r2), c2 = -1 - c1;
        return derivative ? r1 * c1 * Math.exp(r1 * t) + r2 * c2 * Math.exp(r2 * t)
                : c1 * Math.exp(r1 * t) + c2 * Math.exp(r2 * t);
    }
}
