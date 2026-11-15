package com.tentnets.net;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Плоский выпуклый многоугольник в пространстве, натянутый по опорным точкам.
 * Вершины хранятся в мировых координатах и упорядочены против часовой стрелки
 * (если смотреть со стороны нормали).
 */
public final class NetShape {
    public static final double MIN_AREA = 1.0;
    private static final double EPS = 1.0e-6;

    public final Vec3[] verts;
    public final Vec3 center;
    public final Vec3 normal;
    public final Vec3 uAxis;
    public final Vec3 vAxis;
    public final double area;
    public final AABB bounds;

    private NetShape(Vec3[] verts) {
        this.verts = verts;

        double cx = 0, cy = 0, cz = 0;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 v : verts) {
            cx += v.x;
            cy += v.y;
            cz += v.z;
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        int n = verts.length;
        this.center = new Vec3(cx / n, cy / n, cz / n);
        this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        Vec3 v0 = verts[0];
        Vec3 sum = Vec3.ZERO;
        for (int i = 1; i + 1 < n; i++) {
            sum = sum.add(verts[i].subtract(v0).cross(verts[i + 1].subtract(v0)));
        }
        double len = sum.length();
        this.area = len * 0.5;
        this.normal = len > 1.0e-9 ? sum.scale(1.0 / len) : new Vec3(0, 1, 0);

        Vec3 e = verts[1].subtract(v0);
        Vec3 inPlane = e.subtract(normal.scale(e.dot(normal)));
        this.uAxis = inPlane.lengthSqr() > 1.0e-12 ? inPlane.normalize() : new Vec3(1, 0, 0);
        this.vAxis = normal.cross(uAxis);
    }

    /**
     * Строит сеть по опорным точкам: берётся плоскость наилучшего приближения,
     * точки проецируются на неё и берётся их выпуклая оболочка.
     * Возвращает null, если точки на одной линии или площадь слишком мала.
     */
    public static NetShape fromPoints(List<Vec3> pts) {
        int n = pts.size();
        if (n < 3) return null;

        double cx = 0, cy = 0, cz = 0;
        for (Vec3 p : pts) {
            cx += p.x;
            cy += p.y;
            cz += p.z;
        }
        Vec3 c = new Vec3(cx / n, cy / n, cz / n);

        Vec3 best = Vec3.ZERO;
        double bestLen = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Vec3 cr = pts.get(i).subtract(c).cross(pts.get(j).subtract(c));
                double l = cr.lengthSqr();
                if (l > bestLen) {
                    bestLen = l;
                    best = cr;
                }
            }
        }
        if (bestLen < 1.0e-6) return null;
        Vec3 nrm = best.normalize();

        Vec3 u = null;
        double ul = 0;
        for (Vec3 p : pts) {
            Vec3 d = p.subtract(c);
            Vec3 in = d.subtract(nrm.scale(d.dot(nrm)));
            double l = in.lengthSqr();
            if (l > ul) {
                ul = l;
                u = in;
            }
        }
        if (u == null || ul < 1.0e-6) return null;
        u = u.normalize();
        Vec3 v = nrm.cross(u);

        List<double[]> flat = new ArrayList<>(n);
        for (Vec3 p : pts) {
            Vec3 d = p.subtract(c);
            flat.add(new double[]{d.dot(u), d.dot(v)});
        }
        List<double[]> hull = convexHull(flat);
        if (hull.size() < 3) return null;

        Vec3[] out = new Vec3[hull.size()];
        for (int i = 0; i < out.length; i++) {
            double[] h = hull.get(i);
            out[i] = c.add(u.scale(h[0])).add(v.scale(h[1]));
        }
        NetShape shape = new NetShape(out);
        return shape.area >= MIN_AREA ? shape : null;
    }

    private static List<double[]> convexHull(List<double[]> input) {
        List<double[]> pts = new ArrayList<>(input);
        pts.sort(Comparator.<double[]>comparingDouble(p -> p[0]).thenComparingDouble(p -> p[1]));
        int n = pts.size();
        if (n < 3) return pts;

        double[][] h = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross2(h[k - 2], h[k - 1], pts.get(i)) <= EPS) k--;
            h[k++] = pts.get(i);
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross2(h[k - 2], h[k - 1], pts.get(i)) <= EPS) k--;
            h[k++] = pts.get(i);
        }
        List<double[]> res = new ArrayList<>();
        for (int i = 0; i < k - 1; i++) res.add(h[i]);
        return res;
    }

    private static double cross2(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    // ---------------------------------------------------------------- сеть <-> строка (для синхронизации)

    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < verts.length; i++) {
            if (i > 0) sb.append(';');
            Vec3 v = verts[i];
            sb.append(v.x).append(',').append(v.y).append(',').append(v.z);
        }
        return sb.toString();
    }

    public static NetShape decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] parts = s.split(";");
            if (parts.length < 3) return null;
            Vec3[] v = new Vec3[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String[] c = parts[i].split(",");
                if (c.length != 3) return null;
                v[i] = new Vec3(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]));
            }
            return new NetShape(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- геометрия

    /** Координата точки вдоль оси U сети (для текстуры). */
    public double uCoord(Vec3 p) {
        return p.subtract(center).dot(uAxis);
    }

    /** Координата точки вдоль оси V сети (для текстуры). */
    public double vCoord(Vec3 p) {
        return p.subtract(center).dot(vAxis);
    }

    /**
     * Пересечение отрезка a->b с сеткой. margin — «толщина» (радиус летящего объекта).
     * Возвращает точку на плоскости сети или null.
     */
    public Vec3 segmentHit(Vec3 a, Vec3 b, double margin) {
        Vec3 v0 = verts[0];
        double d0 = normal.dot(a.subtract(v0));
        double d1 = normal.dot(b.subtract(v0));
        if ((d0 > margin && d1 > margin) || (d0 < -margin && d1 < -margin)) return null;

        Vec3 p;
        if (Math.abs(d0 - d1) < 1.0e-9) {
            p = b.subtract(normal.scale(d1));
        } else {
            double t = Mth.clamp(d0 / (d0 - d1), 0.0, 1.0);
            p = a.add(b.subtract(a).scale(t));
            p = p.subtract(normal.scale(normal.dot(p.subtract(v0))));
        }
        return contains(p, margin) ? p : null;
    }

    /** Лежит ли точка (уже на плоскости сети) внутри многоугольника с допуском margin. */
    public boolean contains(Vec3 p, double margin) {
        int n = verts.length;
        for (int i = 0; i < n; i++) {
            Vec3 a = verts[i];
            Vec3 e = verts[(i + 1) % n].subtract(a);
            double len = e.length();
            if (len < 1.0e-9) continue;
            double side = e.cross(p.subtract(a)).dot(normal) / len;
            if (side < -margin) return false;
        }
        return true;
    }
}
