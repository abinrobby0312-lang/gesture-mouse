import { normalizeDeg } from './geo';

/**
 * Low-pass filter for compass headings. Smooths the sin and cos components
 * separately so the result never takes the long way round at 0/360.
 */
export class HeadingFilter {
  private sin = 0;
  private cos = 0;
  private primed = false;

  /** `alpha` is the weight of each new sample, in (0, 1]. Lower is smoother. */
  constructor(private readonly alpha = 0.2) {}

  push(deg: number): number {
    const rad = (deg * Math.PI) / 180;
    const s = Math.sin(rad);
    const c = Math.cos(rad);
    if (!this.primed) {
      this.sin = s;
      this.cos = c;
      this.primed = true;
    } else {
      this.sin += this.alpha * (s - this.sin);
      this.cos += this.alpha * (c - this.cos);
    }
    return this.value();
  }

  value(): number {
    return normalizeDeg((Math.atan2(this.sin, this.cos) * 180) / Math.PI);
  }

  reset(): void {
    this.primed = false;
  }
}
