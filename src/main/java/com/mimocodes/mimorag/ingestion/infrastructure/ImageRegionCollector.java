package com.mimocodes.mimorag.ingestion.infrastructure;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;

/**
 * Records where images are drawn on a page and how many vector shapes are filled, without rendering anything. Image
 * boxes are returned in top-left page coordinates after page rotation, like the glyph positions, so slice 2d can OCR
 * exactly these regions of a {@code MIXED} page.
 */
final class ImageRegionCollector extends PDFGraphicsStreamEngine {

	/** Filled shapes smaller than this (points²) are ignored: rules, bullets, table borders. */
	private static final double MIN_FILL_AREA = 4.0;

	private final List<Region> images = new ArrayList<>();

	private final PDRectangle crop;

	private final int rotation;

	private int filledPaths;

	private double minX, minY, maxX, maxY;

	private boolean pathOpen;

	private Point2D.Float current = new Point2D.Float();

	ImageRegionCollector(PDPage page) {
		super(page);
		this.crop = page.getCropBox();
		this.rotation = ((page.getRotation() % 360) + 360) % 360;
	}

	List<Region> images() {
		return List.copyOf(images);
	}

	int filledPaths() {
		return filledPaths;
	}

	@Override
	public void drawImage(PDImage image) {
		Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
		double[] xs = new double[4];
		double[] ys = new double[4];
		float[][] corners = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } };
		for (int i = 0; i < 4; i++) {
			Point2D.Float p = ctm.transformPoint(corners[i][0], corners[i][1]);
			xs[i] = p.x;
			ys[i] = p.y;
		}
		images.add(toPageRegion(min(xs), min(ys), max(xs), max(ys)));
	}

	@Override
	public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
		include(p0);
		include(p1);
		include(p2);
		include(p3);
	}

	@Override
	public void moveTo(float x, float y) {
		include(new Point2D.Float(x, y));
	}

	@Override
	public void lineTo(float x, float y) {
		include(new Point2D.Float(x, y));
	}

	@Override
	public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
		include(new Point2D.Float(x1, y1));
		include(new Point2D.Float(x2, y2));
		include(new Point2D.Float(x3, y3));
	}

	@Override
	public Point2D getCurrentPoint() {
		return current;
	}

	@Override
	public void closePath() {
	}

	@Override
	public void endPath() {
		pathOpen = false;
	}

	@Override
	public void strokePath() {
		pathOpen = false;
	}

	@Override
	public void fillPath(int windingRule) {
		countFill();
	}

	@Override
	public void fillAndStrokePath(int windingRule) {
		countFill();
	}

	@Override
	public void clip(int windingRule) {
	}

	@Override
	public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) throws IOException {
	}

	private void countFill() {
		if (pathOpen && (maxX - minX) * (maxY - minY) >= MIN_FILL_AREA) {
			filledPaths++;
		}
		pathOpen = false;
	}

	private void include(Point2D point) {
		current = new Point2D.Float((float) point.getX(), (float) point.getY());
		if (!pathOpen) {
			minX = maxX = point.getX();
			minY = maxY = point.getY();
			pathOpen = true;
			return;
		}
		minX = Math.min(minX, point.getX());
		maxX = Math.max(maxX, point.getX());
		minY = Math.min(minY, point.getY());
		maxY = Math.max(maxY, point.getY());
	}

	/** PDF user space (bottom-left origin) → top-left page coordinates after rotation, clipped to the page. */
	private Region toPageRegion(double x0, double y0, double x1, double y1) {
		double width = crop.getWidth();
		double height = crop.getHeight();
		double u0 = clamp(x0 - crop.getLowerLeftX(), width), u1 = clamp(x1 - crop.getLowerLeftX(), width);
		double v0 = clamp(crop.getUpperRightY() - y1, height), v1 = clamp(crop.getUpperRightY() - y0, height);
		return switch (rotation) {
			case 90 -> new Region(height - v1, u0, v1 - v0, u1 - u0);
			case 180 -> new Region(width - u1, height - v1, u1 - u0, v1 - v0);
			case 270 -> new Region(v0, width - u1, v1 - v0, u1 - u0);
			default -> new Region(u0, v0, u1 - u0, v1 - v0);
		};
	}

	private static double clamp(double value, double limit) {
		return Math.max(0, Math.min(limit, value));
	}

	private static double min(double[] values) {
		double result = values[0];
		for (double value : values) {
			result = Math.min(result, value);
		}
		return result;
	}

	private static double max(double[] values) {
		double result = values[0];
		for (double value : values) {
			result = Math.max(result, value);
		}
		return result;
	}

}
