/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gridlayout;

import static android.view.Gravity.AXIS_PULL_AFTER;
import static android.view.Gravity.AXIS_PULL_BEFORE;
import static android.view.Gravity.AXIS_SPECIFIED;
import static android.view.Gravity.AXIS_X_SHIFT;
import static android.view.Gravity.AXIS_Y_SHIFT;
import static android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
import static android.view.Gravity.VERTICAL_GRAVITY_MASK;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * This is a fully backwards-compatible version of GridLayout, which works all
 * the way back to Android 1.5.
 * 
 * IMPORTANT: There is one difference between this GridLayout and the one the
 * default. When you change the visibility of a child View, you must also call
 * GridLayout.notifyChildVisibilityChanged(). This workaround exists because
 * there is no other way to detect child visibility changes in a ViewGroup on
 * older versions of Android.
 * 
 * Projects using this class must use at least Android SDK 11+. (It is
 * compatible back to 1.5, but wraps potentially beneficial methods for newer
 * versions of Android.)
 * 
 * @author Daniel Lew (danlew42@gmail.com)
 */
public class GridLayout extends ViewGroup {

	// Public constants

	/**
	 * Alignments specify where a view should be placed within a cell group and
	 * what size it should be.
	 * <p>
	 * The {@link LayoutParams} class contains a {@link LayoutParams#rowSpec
	 * rowSpec} and a {@link LayoutParams#columnSpec columnSpec} each of which
	 * contains an {@code alignment}. Overall placement of the view in the cell
	 * group is specified by the two alignments which act along each axis
	 * independently.
	 * <p>
	 * The GridLayout class defines the most common alignments used in general
	 * layout: {@link #TOP}, {@link #LEFT}, {@link #BOTTOM}, {@link #RIGHT},
	 * {@link #CENTER}, {@link #BASELINE} and {@link #FILL}.
	 */
	/*
	 * An Alignment implementation must define {@link #getAlignmentValue(View,
	 * int, int)}, to return the appropriate value for the type of alignment
	 * being defined. The enclosing algorithms position the children so that the
	 * locations defined by the alignment values are the same for all of the
	 * views in a group. <p>
	 */
	public static abstract class Alignment {
		Alignment() {
		}

		/**
		 * Returns an alignment value. In the case of vertical alignments the
		 * value returned should indicate the distance from the top of the view
		 * to the alignment location. For horizontal alignments measurement is
		 * made from the left edge of the component.
		 * 
		 * @param view
		 *            the view to which this alignment should be applied
		 * @param viewSize
		 *            the measured size of the view
		 * @return the alignment value
		 */
		abstract int getAlignmentValue(View view, int viewSize);

		Bounds getBounds() {
			return new Bounds();
		}

		/**
		 * Returns the size of the view specified by this alignment. In the case
		 * of vertical alignments this method should return a height; for
		 * horizontal alignments this method should return the width.
		 * <p>
		 * The default implementation returns {@code viewSize}.
		 * 
		 * @param view
		 *            the view to which this alignment should be applied
		 * @param viewSize
		 *            the measured size of the view
		 * @param cellSize
		 *            the size of the cell into which this view will be placed
		 * @param measurementType
		 *            This parameter is currently unused as GridLayout only
		 *            supports one type of measurement:
		 *            {@link View#measure(int, int)}.
		 * 
		 * @return the aligned size
		 */
		int getSizeInCell(View view, int viewSize, int cellSize,
				int measurementType) {
			return viewSize;
		}
	}

	/*
	 * In place of a HashMap from span to Int, use an array of key/value pairs -
	 * stored in Arcs. Add the mutables completesCycle flag to avoid creating
	 * another hash table for detecting cycles.
	 */
	final static class Arc {
		public final Interval span;
		public final MutableInt value;
		public boolean valid = true;

		public Arc(Interval span, MutableInt value) {
			this.span = span;
			this.value = value;
		}

		@Override
		public String toString() {
			return this.span + " " + (!this.valid ? "+>" : "->") + " "
					+ this.value;
		}
	}

	final static class Assoc<K, V> extends ArrayList<Pair<K, V>> {
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public static <K, V> Assoc<K, V> of(Class<K> keyType, Class<V> valueType) {
			return new Assoc<K, V>(keyType, valueType);
		}

		private final Class<K> keyType;

		private final Class<V> valueType;

		private Assoc(Class<K> keyType, Class<V> valueType) {
			this.keyType = keyType;
			this.valueType = valueType;
		}

		@SuppressWarnings(value = "unchecked")
		public PackedMap<K, V> pack() {
			int N = this.size();
			K[] keys = (K[]) Array.newInstance(this.keyType, N);
			V[] values = (V[]) Array.newInstance(this.valueType, N);
			for (int i = 0; i < N; i++) {
				keys[i] = this.get(i).first;
				values[i] = this.get(i).second;
			}
			return new PackedMap<K, V>(keys, values);
		}

		public void put(K key, V value) {
			this.add(Pair.create(key, value));
		}
	}

	/*
	 * This internal class houses the algorithm for computing the locations of
	 * grid lines; along either the horizontal or vertical axis. A GridLayout
	 * uses two instances of this class - distinguished by the "horizontal" flag
	 * which is true for the horizontal axis and false for the vertical one.
	 */
	final class Axis {
		private static final int NEW = 0;
		private static final int PENDING = 1;
		private static final int COMPLETE = 2;

		public final boolean horizontal;

		public int definedCount = UNDEFINED;
		private int maxIndex = UNDEFINED;

		PackedMap<Spec, Bounds> groupBounds;
		public boolean groupBoundsValid = false;

		PackedMap<Interval, MutableInt> forwardLinks;
		public boolean forwardLinksValid = false;

		PackedMap<Interval, MutableInt> backwardLinks;
		public boolean backwardLinksValid = false;

		public int[] leadingMargins;
		public boolean leadingMarginsValid = false;

		public int[] trailingMargins;
		public boolean trailingMarginsValid = false;

		public Arc[] arcs;
		public boolean arcsValid = false;

		public int[] locations;
		public boolean locationsValid = false;

		boolean orderPreserved = DEFAULT_ORDER_PRESERVED;

		private final MutableInt parentMin = new MutableInt(0);
		private final MutableInt parentMax = new MutableInt(-MAX_SIZE);

		private Axis(boolean horizontal) {
			this.horizontal = horizontal;
		}

		private void addComponentSizes(List<Arc> result,
				PackedMap<Interval, MutableInt> links) {
			for (int i = 0; i < links.keys.length; i++) {
				Interval key = links.keys[i];
				this.include(result, key, links.values[i], false);
			}
		}

		private String arcsToString(List<Arc> arcs) {
			String var = this.horizontal ? "x" : "y";
			StringBuilder result = new StringBuilder();
			boolean first = true;
			for (Arc arc : arcs) {
				if (first) {
					first = false;
				} else {
					result = result.append(", ");
				}
				int src = arc.span.min;
				int dst = arc.span.max;
				int value = arc.value.value;
				result.append((src < dst) ? var + dst + " - " + var + src
						+ " > " + value : var + src + " - " + var + dst + " < "
						+ -value);

			}
			return result.toString();
		}

		private int calculateMaxIndex() {
			// the number Integer.MIN_VALUE + 1 comes up in undefined cells
			int result = -1;
			for (int i = 0, N = GridLayout.this.getChildCount(); i < N; i++) {
				View c = GridLayout.this.getChildAt(i);
				LayoutParams params = GridLayout.this.getLayoutParams(c);
				Spec spec = this.horizontal ? params.columnSpec
						: params.rowSpec;
				Interval span = spec.span;
				result = max(result, span.min);
				result = max(result, span.max);
			}
			return result == -1 ? UNDEFINED : result;
		}

		private void computeArcs() {
			// getting the links validates the values that are shared by the arc
			// list
			this.getForwardLinks();
			this.getBackwardLinks();
		}

		private void computeGroupBounds() {
			Bounds[] values = this.groupBounds.values;
			for (int i = 0; i < values.length; i++) {
				values[i].reset();
			}
			for (int i = 0, N = GridLayout.this.getChildCount(); i < N; i++) {
				View c = GridLayout.this.getChildAt(i);
				LayoutParams lp = GridLayout.this.getLayoutParams(c);
				Spec spec = this.horizontal ? lp.columnSpec : lp.rowSpec;
				this.groupBounds.getValue(i).include(c, spec, GridLayout.this,
						this);
			}
		}

		private void computeLinks(PackedMap<Interval, MutableInt> links,
				boolean min) {
			MutableInt[] spans = links.values;
			for (int i = 0; i < spans.length; i++) {
				spans[i].reset();
			}

			// Use getter to trigger a re-evaluation
			Bounds[] bounds = this.getGroupBounds().values;
			for (int i = 0; i < bounds.length; i++) {
				int size = bounds[i].size(min);
				MutableInt valueHolder = links.getValue(i);
				// this effectively takes the max() of the minima and the min()
				// of the maxima
				valueHolder.value = max(valueHolder.value, min ? size : -size);
			}
		}

		private void computeLocations(int[] a) {
			this.solve(this.getArcs(), a);
			if (!this.orderPreserved) {
				// Solve returns the smallest solution to the constraint system
				// for which all
				// values are positive. One value is therefore zero - though if
				// the row/col
				// order is not preserved this may not be the first vertex. For
				// consistency,
				// translate all the values so that they measure the distance
				// from a[0]; the
				// leading edge of the parent. After this transformation some
				// values may be
				// negative.
				int a0 = a[0];
				for (int i = 0, N = a.length; i < N; i++) {
					a[i] = a[i] - a0;
				}
			}
		}

		private void computeMargins(boolean leading) {
			int[] margins = leading ? this.leadingMargins
					: this.trailingMargins;
			for (int i = 0, N = GridLayout.this.getChildCount(); i < N; i++) {
				View c = GridLayout.this.getChildAt(i);
				if (GridLayout.this.isGone(c))
					continue;
				LayoutParams lp = GridLayout.this.getLayoutParams(c);
				Spec spec = this.horizontal ? lp.columnSpec : lp.rowSpec;
				Interval span = spec.span;
				int index = leading ? span.min : span.max;
				margins[index] = max(margins[index],
						GridLayout.this.getMargin1(c, this.horizontal, leading));
			}
		}

		private Arc[] createArcs() {
			List<Arc> mins = new ArrayList<Arc>();
			List<Arc> maxs = new ArrayList<Arc>();

			// Add the minimum values from the components.
			this.addComponentSizes(mins, this.getForwardLinks());
			// Add the maximum values from the components.
			this.addComponentSizes(maxs, this.getBackwardLinks());

			// Add ordering constraints to prevent row/col sizes from going
			// negative
			if (this.orderPreserved) {
				// Add a constraint for every row/col
				for (int i = 0; i < this.getCount(); i++) {
					this.include(mins, new Interval(i, i + 1),
							new MutableInt(0));
				}
			}

			// Add the container constraints. Use the version of include that
			// allows
			// duplicate entries in case a child spans the entire grid.
			int N = this.getCount();
			this.include(mins, new Interval(0, N), this.parentMin, false);
			this.include(maxs, new Interval(N, 0), this.parentMax, false);

			// Sort
			Arc[] sMins = this.topologicalSort(mins);
			Arc[] sMaxs = this.topologicalSort(maxs);

			return append(sMins, sMaxs);
		}

		private PackedMap<Spec, Bounds> createGroupBounds() {
			Assoc<Spec, Bounds> assoc = Assoc.of(Spec.class, Bounds.class);
			for (int i = 0, N = GridLayout.this.getChildCount(); i < N; i++) {
				View c = GridLayout.this.getChildAt(i);
				LayoutParams lp = GridLayout.this.getLayoutParams(c);
				Spec spec = this.horizontal ? lp.columnSpec : lp.rowSpec;
				Bounds bounds = GridLayout.this.getAlignment(spec.alignment,
						this.horizontal).getBounds();
				assoc.put(spec, bounds);
			}
			return assoc.pack();
		}

		// Add values computed by alignment - taking the max of all alignments
		// in each span
		private PackedMap<Interval, MutableInt> createLinks(boolean min) {
			Assoc<Interval, MutableInt> result = Assoc.of(Interval.class,
					MutableInt.class);
			Spec[] keys = this.getGroupBounds().keys;
			for (int i = 0, N = keys.length; i < N; i++) {
				Interval span = min ? keys[i].span : keys[i].span.inverse();
				result.put(span, new MutableInt());
			}
			return result.pack();
		}

		public Arc[] getArcs() {
			if (this.arcs == null) {
				this.arcs = this.createArcs();
			}
			if (!this.arcsValid) {
				this.computeArcs();
				this.arcsValid = true;
			}
			return this.arcs;
		}

		private PackedMap<Interval, MutableInt> getBackwardLinks() {
			if (this.backwardLinks == null) {
				this.backwardLinks = this.createLinks(false);
			}
			if (!this.backwardLinksValid) {
				this.computeLinks(this.backwardLinks, false);
				this.backwardLinksValid = true;
			}
			return this.backwardLinks;
		}

		public int getCount() {
			return max(this.definedCount, this.getMaxIndex());
		}

		private PackedMap<Interval, MutableInt> getForwardLinks() {
			if (this.forwardLinks == null) {
				this.forwardLinks = this.createLinks(true);
			}
			if (!this.forwardLinksValid) {
				this.computeLinks(this.forwardLinks, true);
				this.forwardLinksValid = true;
			}
			return this.forwardLinks;
		}

		public PackedMap<Spec, Bounds> getGroupBounds() {
			if (this.groupBounds == null) {
				this.groupBounds = this.createGroupBounds();
			}
			if (!this.groupBoundsValid) {
				this.computeGroupBounds();
				this.groupBoundsValid = true;
			}
			return this.groupBounds;
		}

		public int[] getLeadingMargins() {
			if (this.leadingMargins == null) {
				this.leadingMargins = new int[this.getCount() + 1];
			}
			if (!this.leadingMarginsValid) {
				this.computeMargins(true);
				this.leadingMarginsValid = true;
			}
			return this.leadingMargins;
		}

		public int[] getLocations() {
			if (this.locations == null) {
				int N = this.getCount() + 1;
				this.locations = new int[N];
			}
			if (!this.locationsValid) {
				this.computeLocations(this.locations);
				this.locationsValid = true;
			}
			return this.locations;
		}

		private int getMaxIndex() {
			if (this.maxIndex == UNDEFINED) {
				this.maxIndex = max(0, this.calculateMaxIndex()); // use zero
																	// when
																	// there are
																	// no
																	// children
			}
			return this.maxIndex;
		}

		public int getMeasure(int measureSpec) {
			int mode = MeasureSpec.getMode(measureSpec);
			int size = MeasureSpec.getSize(measureSpec);
			switch (mode) {
			case MeasureSpec.UNSPECIFIED: {
				return this.getMeasure(0, MAX_SIZE);
			}
			case MeasureSpec.EXACTLY: {
				return this.getMeasure(size, size);
			}
			case MeasureSpec.AT_MOST: {
				return this.getMeasure(0, size);
			}
			default: {
				assert false;
				return 0;
			}
			}
		}

		private int getMeasure(int min, int max) {
			this.setParentConstraints(min, max);
			return this.size(this.getLocations());
		}

		public int[] getTrailingMargins() {
			if (this.trailingMargins == null) {
				this.trailingMargins = new int[this.getCount() + 1];
			}
			if (!this.trailingMarginsValid) {
				this.computeMargins(false);
				this.trailingMarginsValid = true;
			}
			return this.trailingMargins;
		}

		// Group arcs by their first vertex, returning an array of arrays.
		// This is linear in the number of arcs.
		Arc[][] groupArcsByFirstVertex(Arc[] arcs) {
			int N = this.getCount() + 1; // the number of vertices
			Arc[][] result = new Arc[N][];
			int[] sizes = new int[N];
			for (Arc arc : arcs) {
				sizes[arc.span.min]++;
			}
			for (int i = 0; i < sizes.length; i++) {
				result[i] = new Arc[sizes[i]];
			}
			// reuse the sizes array to hold the current last elements as we
			// insert each arc
			Arrays.fill(sizes, 0);
			for (Arc arc : arcs) {
				int i = arc.span.min;
				result[i][sizes[i]++] = arc;
			}

			return result;
		}

		private void include(List<Arc> arcs, Interval key, MutableInt size) {
			this.include(arcs, key, size, true);
		}

		private void include(List<Arc> arcs, Interval key, MutableInt size,
				boolean ignoreIfAlreadyPresent) {
			/*
			 * Remove self referential links. These appear: . as parental
			 * constraints when GridLayout has no children . when components
			 * have been marked as GONE
			 */
			if (key.size() == 0) {
				return;
			}
			// this bit below should really be computed outside here -
			// its just to stop default (row/col > 0) constraints obliterating
			// valid entries
			if (ignoreIfAlreadyPresent) {
				for (Arc arc : arcs) {
					Interval span = arc.span;
					if (span.equals(key)) {
						return;
					}
				}
			}
			arcs.add(new Arc(key, size));
		}

		private void init(int[] locations) {
			Arrays.fill(locations, 0);
		}

		public void invalidateStructure() {
			this.maxIndex = UNDEFINED;

			this.groupBounds = null;
			this.forwardLinks = null;
			this.backwardLinks = null;

			this.leadingMargins = null;
			this.trailingMargins = null;
			this.arcs = null;

			this.locations = null;

			this.invalidateValues();
		}

		public void invalidateValues() {
			this.groupBoundsValid = false;
			this.forwardLinksValid = false;
			this.backwardLinksValid = false;

			this.leadingMarginsValid = false;
			this.trailingMarginsValid = false;
			this.arcsValid = false;

			this.locationsValid = false;
		}

		// External entry points

		public boolean isOrderPreserved() {
			return this.orderPreserved;
		}

		public void layout(int size) {
			this.setParentConstraints(size, size);
			this.getLocations();
		}

		private void logError(String axisName, Arc[] arcs, boolean[] culprits0) {
			List<Arc> culprits = new ArrayList<Arc>();
			List<Arc> removed = new ArrayList<Arc>();
			for (int c = 0; c < arcs.length; c++) {
				Arc arc = arcs[c];
				if (culprits0[c]) {
					culprits.add(arc);
				}
				if (!arc.valid) {
					removed.add(arc);
				}
			}
			Log.d(TAG,
					axisName + " constraints: " + this.arcsToString(culprits)
							+ " are inconsistent; " + "permanently removing: "
							+ this.arcsToString(removed) + ". ");
		}

		private boolean relax(int[] locations, Arc entry) {
			if (!entry.valid) {
				return false;
			}
			Interval span = entry.span;
			int u = span.min;
			int v = span.max;
			int value = entry.value.value;
			int candidate = locations[u] + value;
			if (candidate > locations[v]) {
				locations[v] = candidate;
				return true;
			}
			return false;
		}

		public void setCount(int count) {
			this.definedCount = count;
		}

		public void setOrderPreserved(boolean orderPreserved) {
			this.orderPreserved = orderPreserved;
			this.invalidateStructure();
		}

		private void setParentConstraints(int min, int max) {
			this.parentMin.value = min;
			this.parentMax.value = -max;
			this.locationsValid = false;
		}

		private int size(int[] locations) {
			// The parental edges are attached to vertices 0 and N - even when
			// order is not
			// being preserved and other vertices fall outside this range.
			// Measure the distance
			// between vertices 0 and N, assuming that locations[0] = 0.
			return locations[this.getCount()];
		}

		/*
		 * Bellman-Ford variant - modified to reduce typical running time from
		 * O(N^2) to O(N)
		 * 
		 * GridLayout converts its requirements into a system of linear
		 * constraints of the form:
		 * 
		 * x[i] - x[j] < a[k]
		 * 
		 * Where the x[i] are variables and the a[k] are constants.
		 * 
		 * For example, if the variables were instead labeled x, y, z we might
		 * have:
		 * 
		 * x - y < 17 y - z < 23 z - x < 42
		 * 
		 * This is a special case of the Linear Programming problem that is, in
		 * turn, equivalent to the single-source shortest paths problem on a
		 * digraph, for which the O(n^2) Bellman-Ford algorithm the most
		 * commonly used general solution.
		 * 
		 * Other algorithms are faster in the case where no arcs have negative
		 * weights but allowing negative weights turns out to be the same as
		 * accommodating maximum size requirements as well as minimum ones.
		 * 
		 * Bellman-Ford works by iteratively 'relaxing' constraints over all
		 * nodes (an O(N) process) and performing this step N times. Proof of
		 * correctness hinges on the fact that there can be no negative weight
		 * chains of length > N - unless a 'negative weight loop' exists. The
		 * algorithm catches this case in a final checking phase that reports
		 * failure.
		 * 
		 * By topologically sorting the nodes and checking this condition at
		 * each step typical layout problems complete after the first iteration
		 * and the algorithm completes in O(N) steps with very low constants.
		 */
		private void solve(Arc[] arcs, int[] locations) {
			String axisName = this.horizontal ? "horizontal" : "vertical";
			int N = this.getCount() + 1; // The number of vertices is the number
											// of columns/rows + 1.
			boolean[] originalCulprits = null;

			for (int p = 0; p < arcs.length; p++) {
				this.init(locations);

				// We take one extra pass over traditional Bellman-Ford (and
				// omit their final step)
				for (int i = 0; i < N; i++) {
					boolean changed = false;
					for (int j = 0, length = arcs.length; j < length; j++) {
						changed |= this.relax(locations, arcs[j]);
					}
					if (!changed) {
						if (originalCulprits != null) {
							this.logError(axisName, arcs, originalCulprits);
						}
						return;
					}
				}

				boolean[] culprits = new boolean[arcs.length];
				for (int i = 0; i < N; i++) {
					for (int j = 0, length = arcs.length; j < length; j++) {
						culprits[j] |= this.relax(locations, arcs[j]);
					}
				}

				if (p == 0) {
					originalCulprits = culprits;
				}

				for (int i = 0; i < arcs.length; i++) {
					if (culprits[i]) {
						Arc arc = arcs[i];
						// Only remove max values, min values alone cannot be
						// inconsistent
						if (arc.span.min < arc.span.max) {
							continue;
						}
						arc.valid = false;
						break;
					}
				}
			}
		}

		private Arc[] topologicalSort(final Arc[] arcs) {
			return new Object() {
				Arc[] result = new Arc[arcs.length];
				int cursor = this.result.length - 1;
				Arc[][] arcsByVertex = Axis.this.groupArcsByFirstVertex(arcs);
				int[] visited = new int[Axis.this.getCount() + 1];

				Arc[] sort() {
					for (int loc = 0, N = this.arcsByVertex.length; loc < N; loc++) {
						this.walk(loc);
					}
					assert this.cursor == -1;
					return this.result;
				}

				void walk(int loc) {
					switch (this.visited[loc]) {
					case NEW: {
						this.visited[loc] = PENDING;
						for (Arc arc : this.arcsByVertex[loc]) {
							this.walk(arc.span.max);
							this.result[this.cursor--] = arc;
						}
						this.visited[loc] = COMPLETE;
						break;
					}
					case PENDING: {
						assert false;
						break;
					}
					case COMPLETE: {
						break;
					}
					}
				}
			}.sort();
		}

		private Arc[] topologicalSort(List<Arc> arcs) {
			return this.topologicalSort(arcs.toArray(new Arc[arcs.size()]));
		}
	}

	/*
	 * For each group (with a given alignment) we need to store the amount of
	 * space required before the alignment point and the amount of space
	 * required after it. One side of this calculation is always 0 for LEADING
	 * and TRAILING alignments but we don't make use of this. For CENTER and
	 * BASELINE alignments both sides are needed and in the BASELINE case no
	 * simple optimisations are possible.
	 * 
	 * The general algorithm therefore is to create a Map (actually a PackedMap)
	 * from group to Bounds and to loop through all Views in the group taking
	 * the maximum of the values for each View.
	 */
	static class Bounds {
		public int before;
		public int after;
		public int flexibility; // we're flexible iff all included specs are
								// flexible

		private Bounds() {
			this.reset();
		}

		protected int getOffset(View c, Alignment alignment, int size) {
			return this.before - alignment.getAlignmentValue(c, size);
		}

		protected void include(int before, int after) {
			this.before = max(this.before, before);
			this.after = max(this.after, after);
		}

		protected final void include(View c, Spec spec, GridLayout gridLayout,
				Axis axis) {
			this.flexibility &= spec.getFlexibility();
			int size = gridLayout.getMeasurementIncludingMargin(c,
					axis.horizontal);
			Alignment alignment = gridLayout.getAlignment(spec.alignment,
					axis.horizontal);
			// todo test this works correctly when the returned value is
			// UNDEFINED
			int before = alignment.getAlignmentValue(c, size);
			this.include(before, size - before);
		}

		protected void reset() {
			this.before = Integer.MIN_VALUE;
			this.after = Integer.MIN_VALUE;
			this.flexibility = CAN_STRETCH; // from the above, we're flexible
											// when empty
		}

		protected int size(boolean min) {
			if (!min) {
				if (canStretch(this.flexibility)) {
					return MAX_SIZE;
				}
			}
			return this.before + this.after;
		}

		@Override
		public String toString() {
			return "Bounds{" + "before=" + this.before + ", after="
					+ this.after + '}';
		}
	}

	// Misc constants

	/**
	 * An Interval represents a contiguous range of values that lie between the
	 * interval's {@link #min} and {@link #max} values.
	 * <p>
	 * Intervals are immutable so may be passed as values and used as keys in
	 * hash tables. It is not necessary to have multiple instances of Intervals
	 * which have the same {@link #min} and {@link #max} values.
	 * <p>
	 * Intervals are often written as {@code [min, max]} and represent the set
	 * of values {@code x} such that {@code min <= x < max}.
	 */
	final static class Interval {
		/**
		 * The minimum value.
		 */
		public final int min;

		/**
		 * The maximum value.
		 */
		public final int max;

		/**
		 * Construct a new Interval, {@code interval}, where:
		 * <ul>
		 * <li> {@code interval.min = min}</li>
		 * <li> {@code interval.max = max}</li>
		 * </ul>
		 * 
		 * @param min
		 *            the minimum value.
		 * @param max
		 *            the maximum value.
		 */
		public Interval(int min, int max) {
			this.min = min;
			this.max = max;
		}

		/**
		 * Returns {@code true} if the {@link #getClass class}, {@link #min} and
		 * {@link #max} properties of this Interval and the supplied parameter
		 * are pairwise equal; {@code false} otherwise.
		 * 
		 * @param that
		 *            the object to compare this interval with
		 * 
		 * @return {@code true} if the specified object is equal to this
		 *         {@code Interval}, {@code false} otherwise.
		 */
		@Override
		public boolean equals(Object that) {
			if (this == that) {
				return true;
			}
			if (that == null || this.getClass() != that.getClass()) {
				return false;
			}

			Interval interval = (Interval) that;

			if (this.max != interval.max) {
				return false;
			}
			// noinspection RedundantIfStatement
			if (this.min != interval.min) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			int result = this.min;
			result = 31 * result + this.max;
			return result;
		}

		Interval inverse() {
			return new Interval(this.max, this.min);
		}

		int size() {
			return this.max - this.min;
		}

		@Override
		public String toString() {
			return "[" + this.min + ", " + this.max + "]";
		}
	}

	/**
	 * Layout information associated with each of the children of a GridLayout.
	 * <p>
	 * GridLayout supports both row and column spanning and arbitrary forms of
	 * alignment within each cell group. The fundamental parameters associated
	 * with each cell group are gathered into their vertical and horizontal
	 * components and stored in the {@link #rowSpec} and {@link #columnSpec}
	 * layout parameters. {@link android.widget.GridLayout.Spec Specs} are
	 * immutable structures and may be shared between the layout parameters of
	 * different children.
	 * <p>
	 * The row and column specs contain the leading and trailing indices along
	 * each axis and together specify the four grid indices that delimit the
	 * cells of this cell group.
	 * <p>
	 * The alignment properties of the row and column specs together specify
	 * both aspects of alignment within the cell group. It is also possible to
	 * specify a child's alignment within its cell group by using the
	 * {@link GridLayout.LayoutParams#setGravity(int)} method.
	 * 
	 * <h4>WRAP_CONTENT and MATCH_PARENT</h4>
	 * 
	 * Because the default values of the {@link #width} and {@link #height}
	 * properties are both {@link #WRAP_CONTENT}, this value never needs to be
	 * explicitly declared in the layout parameters of GridLayout's children. In
	 * addition, GridLayout does not distinguish the special size value
	 * {@link #MATCH_PARENT} from {@link #WRAP_CONTENT}. A component's ability
	 * to expand to the size of the parent is instead controlled by the
	 * principle of <em>flexibility</em>, as discussed in {@link GridLayout}.
	 * 
	 * <h4>Summary</h4>
	 * 
	 * You should not need to use either of the special size values:
	 * {@code WRAP_CONTENT} or {@code MATCH_PARENT} when configuring the
	 * children of a GridLayout.
	 * 
	 * <h4>Default values</h4>
	 * 
	 * <ul>
	 * <li>{@link #width} = {@link #WRAP_CONTENT}</li>
	 * <li>{@link #height} = {@link #WRAP_CONTENT}</li>
	 * <li>{@link #topMargin} = 0 when
	 * {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
	 * {@code false}; otherwise {@link #UNDEFINED}, to indicate that a default
	 * value should be computed on demand.</li>
	 * <li>{@link #leftMargin} = 0 when
	 * {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
	 * {@code false}; otherwise {@link #UNDEFINED}, to indicate that a default
	 * value should be computed on demand.</li>
	 * <li>{@link #bottomMargin} = 0 when
	 * {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
	 * {@code false}; otherwise {@link #UNDEFINED}, to indicate that a default
	 * value should be computed on demand.</li>
	 * <li>{@link #rightMargin} = 0 when
	 * {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
	 * {@code false}; otherwise {@link #UNDEFINED}, to indicate that a default
	 * value should be computed on demand.</li>
	 * <li>{@link #rowSpec}<code>.row</code> = {@link #UNDEFINED}</li>
	 * <li>{@link #rowSpec}<code>.rowSpan</code> = 1</li>
	 * <li>{@link #rowSpec}<code>.alignment</code> = {@link #BASELINE}</li>
	 * <li>{@link #columnSpec}<code>.column</code> = {@link #UNDEFINED}</li>
	 * <li>{@link #columnSpec}<code>.columnSpan</code> = 1</li>
	 * <li>{@link #columnSpec}<code>.alignment</code> = {@link #LEFT}</li>
	 * </ul>
	 * 
	 * See {@link GridLayout} for a more complete description of the conventions
	 * used by GridLayout in the interpretation of the properties of this class.
	 * 
	 * @attr ref android.R.styleable#GridLayout_Layout_layout_row
	 * @attr ref android.R.styleable#GridLayout_Layout_layout_rowSpan
	 * @attr ref android.R.styleable#GridLayout_Layout_layout_column
	 * @attr ref android.R.styleable#GridLayout_Layout_layout_columnSpan
	 * @attr ref android.R.styleable#GridLayout_Layout_layout_gravity
	 */
	public static class LayoutParams extends MarginLayoutParams {

		// Default values

		private static final int DEFAULT_WIDTH = WRAP_CONTENT;
		private static final int DEFAULT_HEIGHT = WRAP_CONTENT;
		private static final int DEFAULT_MARGIN = UNDEFINED;
		private static final int DEFAULT_ROW = UNDEFINED;
		private static final int DEFAULT_COLUMN = UNDEFINED;
		private static final Interval DEFAULT_SPAN = new Interval(UNDEFINED,
				UNDEFINED + 1);
		private static final int DEFAULT_SPAN_SIZE = DEFAULT_SPAN.size();

		// TypedArray indices

		private static final int MARGIN = R.styleable.ViewGroup_MarginLayout_android_layout_margin;
		private static final int LEFT_MARGIN = R.styleable.ViewGroup_MarginLayout_android_layout_marginLeft;
		private static final int TOP_MARGIN = R.styleable.ViewGroup_MarginLayout_android_layout_marginTop;
		private static final int RIGHT_MARGIN = R.styleable.ViewGroup_MarginLayout_android_layout_marginRight;
		private static final int BOTTOM_MARGIN = R.styleable.ViewGroup_MarginLayout_android_layout_marginBottom;

		private static final int COLUMN = R.styleable.GridLayout_Layout_android_layout_column;
		private static final int COLUMN_SPAN = R.styleable.GridLayout_Layout_layout_columnSpan;

		private static final int ROW = R.styleable.GridLayout_Layout_layout_row;
		private static final int ROW_SPAN = R.styleable.GridLayout_Layout_layout_rowSpan;

		private static final int GRAVITY = R.styleable.GridLayout_Layout_android_layout_gravity;

		// Instance variables

		/**
		 * The spec that defines the vertical characteristics of the cell group
		 * described by these layout parameters.
		 */
		public Spec rowSpec = Spec.UNDEFINED;

		/**
		 * The spec that defines the horizontal characteristics of the cell
		 * group described by these layout parameters.
		 */
		public Spec columnSpec = Spec.UNDEFINED;

		// Constructors

		/**
		 * Constructs a new LayoutParams with default values as defined in
		 * {@link LayoutParams}.
		 */
		public LayoutParams() {
			this(Spec.UNDEFINED, Spec.UNDEFINED);
		}

		/**
		 * {@inheritDoc}
		 * 
		 * Values not defined in the attribute set take the default values
		 * defined in {@link LayoutParams}.
		 */
		public LayoutParams(Context context, AttributeSet attrs) {
			super(context, attrs);
			this.reInitSuper(context, attrs);
			this.init(context, attrs);
		}

		private LayoutParams(int width, int height, int left, int top,
				int right, int bottom, Spec rowSpec, Spec columnSpec) {
			super(width, height);
			this.setMargins(left, top, right, bottom);
			this.rowSpec = rowSpec;
			this.columnSpec = columnSpec;
		}

		// Copying constructors

		/**
		 * {@inheritDoc}
		 */
		public LayoutParams(LayoutParams that) {
			super(that);
			this.rowSpec = that.rowSpec;
			this.columnSpec = that.columnSpec;
		}

		/**
		 * {@inheritDoc}
		 */
		public LayoutParams(MarginLayoutParams params) {
			super(params);
		}

		/**
		 * Constructs a new LayoutParams instance for this <code>rowSpec</code>
		 * and <code>columnSpec</code>. All other fields are initialized with
		 * default values as defined in {@link LayoutParams}.
		 * 
		 * @param rowSpec
		 *            the rowSpec
		 * @param columnSpec
		 *            the columnSpec
		 */
		public LayoutParams(Spec rowSpec, Spec columnSpec) {
			this(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MARGIN, DEFAULT_MARGIN,
					DEFAULT_MARGIN, DEFAULT_MARGIN, rowSpec, columnSpec);
		}

		// AttributeSet constructors

		/**
		 * {@inheritDoc}
		 */
		public LayoutParams(ViewGroup.LayoutParams params) {
			super(params);
		}

		// Implementation

		// Reinitialise the margins using a different default policy than
		// MarginLayoutParams.
		// Here we use the value UNDEFINED (as distinct from zero) to represent
		// the undefined state
		// so that a layout manager default can be accessed post set up. We need
		// this as, at the
		// point of installation, we do not know how many rows/cols there are
		// and therefore
		// which elements are positioned next to the container's trailing edges.
		// We need to
		// know this as margins around the container's boundary should have
		// different
		// defaults to those between peers.

		private void init(Context context, AttributeSet attrs) {
			TypedArray a = context.obtainStyledAttributes(attrs,
					R.styleable.GridLayout_Layout);
			try {
				int gravity = a.getInt(GRAVITY, Gravity.NO_GRAVITY);

				int column = a.getInt(COLUMN, DEFAULT_COLUMN);
				int colSpan = a.getInt(COLUMN_SPAN, DEFAULT_SPAN_SIZE);
				this.columnSpec = spec(column, colSpan,
						getAlignment(gravity, true));

				int row = a.getInt(ROW, DEFAULT_ROW);
				int rowSpan = a.getInt(ROW_SPAN, DEFAULT_SPAN_SIZE);
				this.rowSpec = spec(row, rowSpan, getAlignment(gravity, false));
			} finally {
				a.recycle();
			}
		}

		// This method could be parametrized and moved into MarginLayout.
		private void reInitSuper(Context context, AttributeSet attrs) {
			TypedArray a = context.obtainStyledAttributes(attrs,
					R.styleable.ViewGroup_MarginLayout);
			try {
				int margin = a.getDimensionPixelSize(MARGIN, DEFAULT_MARGIN);

				this.leftMargin = a.getDimensionPixelSize(LEFT_MARGIN, margin);
				this.topMargin = a.getDimensionPixelSize(TOP_MARGIN, margin);
				this.rightMargin = a
						.getDimensionPixelSize(RIGHT_MARGIN, margin);
				this.bottomMargin = a.getDimensionPixelSize(BOTTOM_MARGIN,
						margin);
			} finally {
				a.recycle();
			}
		}

		@Override
		protected void setBaseAttributes(TypedArray attributes, int widthAttr,
				int heightAttr) {
			this.width = attributes
					.getLayoutDimension(widthAttr, DEFAULT_WIDTH);
			this.height = attributes.getLayoutDimension(heightAttr,
					DEFAULT_HEIGHT);
		}

		final void setColumnSpecSpan(Interval span) {
			this.columnSpec = this.columnSpec.copyWriteSpan(span);
		}

		/**
		 * Describes how the child views are positioned. Default is
		 * {@code LEFT | BASELINE}. See {@link android.view.Gravity}.
		 * 
		 * @param gravity
		 *            the new gravity value
		 * 
		 * @attr ref android.R.styleable#GridLayout_Layout_layout_gravity
		 */
		public void setGravity(int gravity) {
			this.rowSpec = this.rowSpec.copyWriteAlignment(getAlignment(
					gravity, false));
			this.columnSpec = this.columnSpec.copyWriteAlignment(getAlignment(
					gravity, true));
		}

		final void setRowSpecSpan(Interval span) {
			this.rowSpec = this.rowSpec.copyWriteSpan(span);
		}
	}

	final static class MutableInt {
		public int value;

		public MutableInt() {
			this.reset();
		}

		public MutableInt(int value) {
			this.value = value;
		}

		public void reset() {
			this.value = Integer.MIN_VALUE;
		}

		@Override
		public String toString() {
			return Integer.toString(this.value);
		}
	}

	/*
	 * This data structure is used in place of a Map where we have an index that
	 * refers to the order in which each key/value pairs were added to the map.
	 * In this case we store keys and values in arrays of a length that is equal
	 * to the number of unique keys. We also maintain an array of indexes from
	 * insertion order to the compacted arrays of keys and values.
	 * 
	 * Note that behavior differs from that of a LinkedHashMap in that repeated
	 * entriesdo* get added multiples times. So the length of index is equals to
	 * the number of items added.
	 * 
	 * This is useful in the GridLayout class where we can rely on the order of
	 * children not changing during layout - to use integer-based lookup for our
	 * internal structures rather than using (and storing) an implementation of
	 * Map<Key, ?>.
	 */
	@SuppressWarnings(value = "unchecked")
	final static class PackedMap<K, V> {
		/*
		 * Create a compact array of keys or values using the supplied index.
		 */
		private static <K> K[] compact(K[] a, int[] index) {
			int size = a.length;
			Class<?> componentType = a.getClass().getComponentType();
			K[] result = (K[]) Array.newInstance(componentType,
					max2(index, -1) + 1);

			// this overwrite duplicates, retaining the last equivalent entry
			for (int i = 0; i < size; i++) {
				result[index[i]] = a[i];
			}
			return result;
		}

		private static <K> int[] createIndex(K[] keys) {
			int size = keys.length;
			int[] result = new int[size];

			Map<K, Integer> keyToIndex = new HashMap<K, Integer>();
			for (int i = 0; i < size; i++) {
				K key = keys[i];
				Integer index = keyToIndex.get(key);
				if (index == null) {
					index = keyToIndex.size();
					keyToIndex.put(key, index);
				}
				result[i] = index;
			}
			return result;
		}

		public final int[] index;

		public final K[] keys;

		public final V[] values;

		private PackedMap(K[] keys, V[] values) {
			this.index = createIndex(keys);

			this.keys = compact(keys, this.index);
			this.values = compact(values, this.index);
		}

		public V getValue(int i) {
			return this.values[this.index[i]];
		}
	}

	private static class Pair<F, S> {
		public static <A, B> Pair<A, B> create(A a, B b) {
			return new Pair<A, B>(a, b);
		}

		public final F first;

		public final S second;

		public Pair(F first, S second) {
			this.first = first;
			this.second = second;
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean equals(Object o) {
			if (o == this)
				return true;
			if (!(o instanceof Pair))
				return false;
			final Pair<F, S> other;
			try {
				other = (Pair<F, S>) o;
			} catch (ClassCastException e) {
				return false;
			}
			return this.first.equals(other.first)
					&& this.second.equals(other.second);
		}

		@Override
		public int hashCode() {
			int result = 17;
			result = 31 * result + this.first.hashCode();
			result = 31 * result + this.second.hashCode();
			return result;
		}
	}

	// Defaults

	private static class ResolveSizeAndStateWrapper {
		static {
			try {
				View.class.getMethod("resolveSizeAndState", int.class,
						int.class, int.class);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}

		public static void checkAvailable() {
		}

		/** copied from android.view.View source **/
		public static int resolveSizeAndState(int size, int measureSpec,
				int childMeasuredState) {
			int result = size;
			int specMode = MeasureSpec.getMode(measureSpec);
			int specSize = MeasureSpec.getSize(measureSpec);
			switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				result = size;
				break;
			case MeasureSpec.AT_MOST:
				if (specSize < size) {
					result = specSize | MEASURED_STATE_TOO_SMALL;
				} else {
					result = size;
				}
				break;
			case MeasureSpec.EXACTLY:
				result = specSize;
				break;
			}
			return result | (childMeasuredState & MEASURED_STATE_MASK);
		}
	}

	/**
	 * A Spec defines the horizontal or vertical characteristics of a group of
	 * cells. Each spec. defines the <em>grid indices</em> and
	 * <em>alignment</em> along the appropriate axis.
	 * <p>
	 * The <em>grid indices</em> are the leading and trailing edges of this cell
	 * group. See {@link GridLayout} for a description of the conventions used
	 * by GridLayout for grid indices.
	 * <p>
	 * The <em>alignment</em> property specifies how cells should be aligned in
	 * this group. For row groups, this specifies the vertical alignment. For
	 * column groups, this specifies the horizontal alignment.
	 * <p>
	 * Use the following static methods to create specs:
	 * <ul>
	 * <li>{@link #spec(int)}</li>
	 * <li>{@link #spec(int, int)}</li>
	 * <li>{@link #spec(int, Alignment)}</li>
	 * <li>{@link #spec(int, int, Alignment)}</li>
	 * </ul>
	 * 
	 */
	public static class Spec {
		static final Spec UNDEFINED = spec(GridLayout.UNDEFINED);

		final boolean startDefined;
		final Interval span;
		final Alignment alignment;

		private Spec(boolean startDefined, int start, int size,
				Alignment alignment) {
			this(startDefined, new Interval(start, start + size), alignment);
		}

		private Spec(boolean startDefined, Interval span, Alignment alignment) {
			this.startDefined = startDefined;
			this.span = span;
			this.alignment = alignment;
		}

		final Spec copyWriteAlignment(Alignment alignment) {
			return new Spec(this.startDefined, this.span, alignment);
		}

		final Spec copyWriteSpan(Interval span) {
			return new Spec(this.startDefined, span, this.alignment);
		}

		/**
		 * Returns {@code true} if the {@code class}, {@code alignment} and
		 * {@code span} properties of this Spec and the supplied parameter are
		 * pairwise equal, {@code false} otherwise.
		 * 
		 * @param that
		 *            the object to compare this spec with
		 * 
		 * @return {@code true} if the specified object is equal to this
		 *         {@code Spec}; {@code false} otherwise
		 */
		@Override
		public boolean equals(Object that) {
			if (this == that) {
				return true;
			}
			if (that == null || this.getClass() != that.getClass()) {
				return false;
			}

			Spec spec = (Spec) that;

			if (!this.alignment.equals(spec.alignment)) {
				return false;
			}
			// noinspection RedundantIfStatement
			if (!this.span.equals(spec.span)) {
				return false;
			}

			return true;
		}

		final int getFlexibility() {
			return (this.alignment == UNDEFINED_ALIGNMENT) ? INFLEXIBLE
					: CAN_STRETCH;
		}

		@Override
		public int hashCode() {
			int result = this.span.hashCode();
			result = 31 * result + this.alignment.hashCode();
			return result;
		}
	}

	/**
	 * The horizontal orientation.
	 */
	public static final int HORIZONTAL = LinearLayout.HORIZONTAL;
	/**
	 * The vertical orientation.
	 */
	public static final int VERTICAL = LinearLayout.VERTICAL;
	/**
	 * The constant used to indicate that a value is undefined. Fields can use
	 * this value to indicate that their values have not yet been set.
	 * Similarly, methods can return this value to indicate that there is no
	 * suitable value that the implementation can return. The value used for the
	 * constant (currently {@link Integer#MIN_VALUE}) is intended to avoid
	 * confusion between valid values whose sign may not be known.
	 */
	public static final int UNDEFINED = Integer.MIN_VALUE;

	// TypedArray indices

	/**
	 * This constant is an {@link #setAlignmentMode(int) alignmentMode}. When
	 * the {@code alignmentMode} is set to {@link #ALIGN_BOUNDS}, alignment is
	 * made between the edges of each component's raw view boundary: i.e. the
	 * area delimited by the component's: {@link android.view.View#getTop() top}
	 * , {@link android.view.View#getLeft() left},
	 * {@link android.view.View#getBottom() bottom} and
	 * {@link android.view.View#getRight() right} properties.
	 * <p>
	 * For example, when {@code GridLayout} is in {@link #ALIGN_BOUNDS} mode,
	 * children that belong to a row group that uses {@link #TOP} alignment will
	 * all return the same value when their {@link android.view.View#getTop()}
	 * method is called.
	 * 
	 * @see #setAlignmentMode(int)
	 */
	public static final int ALIGN_BOUNDS = 0;
	/**
	 * This constant is an {@link #setAlignmentMode(int) alignmentMode}. When
	 * the {@code alignmentMode} is set to {@link #ALIGN_MARGINS}, the bounds of
	 * each view are extended outwards, according to their margins, before the
	 * edges of the resulting rectangle are aligned.
	 * <p>
	 * For example, when {@code GridLayout} is in {@link #ALIGN_MARGINS} mode,
	 * the quantity {@code top - layoutParams.topMargin} is the same for all
	 * children that belong to a row group that uses {@link #TOP} alignment.
	 * 
	 * @see #setAlignmentMode(int)
	 */
	public static final int ALIGN_MARGINS = 1;
	static final String TAG = GridLayout.class.getName();
	static final boolean DEBUG = false;
	static final int PRF = 1;
	static final int MAX_SIZE = 100000;
	static final int DEFAULT_CONTAINER_MARGIN = 0;

	// Instance variables

	private static final int DEFAULT_ORIENTATION = HORIZONTAL;
	private static final int DEFAULT_COUNT = UNDEFINED;
	private static final boolean DEFAULT_USE_DEFAULT_MARGINS = false;
	private static final boolean DEFAULT_ORDER_PRESERVED = true;
	private static final int DEFAULT_ALIGNMENT_MODE = ALIGN_MARGINS;
	private static final int ORIENTATION = R.styleable.GridLayout_android_orientation;
	private static final int ROW_COUNT = R.styleable.GridLayout_rowCount;

	private static final int COLUMN_COUNT = R.styleable.GridLayout_columnCount;

	// Constructors

	private static final int USE_DEFAULT_MARGINS = R.styleable.GridLayout_useDefaultMargins;

	private static final int ALIGNMENT_MODE = R.styleable.GridLayout_alignmentMode;

	private static final int ROW_ORDER_PRESERVED = R.styleable.GridLayout_rowOrderPreserved;

	// Implementation

	private static final int COLUMN_ORDER_PRESERVED = R.styleable.GridLayout_columnOrderPreserved;

	static final Alignment UNDEFINED_ALIGNMENT = new Alignment() {
		@Override
		public int getAlignmentValue(View view, int viewSize) {
			return UNDEFINED;
		}
	};

	private static final Alignment LEADING = new Alignment() {
		@Override
		public int getAlignmentValue(View view, int viewSize) {
			return 0;
		}
	};

	private static final Alignment TRAILING = new Alignment() {
		@Override
		public int getAlignmentValue(View view, int viewSize) {
			return viewSize;
		}
	};

	/**
	 * Indicates that a view should be aligned with the <em>top</em> edges of
	 * the other views in its cell group.
	 */
	public static final Alignment TOP = LEADING;

	/**
	 * Indicates that a view should be aligned with the <em>bottom</em> edges of
	 * the other views in its cell group.
	 */
	public static final Alignment BOTTOM = TRAILING;

	/**
	 * Indicates that a view should be aligned with the <em>right</em> edges of
	 * the other views in its cell group.
	 */
	public static final Alignment RIGHT = TRAILING;

	/**
	 * Indicates that a view should be aligned with the <em>left</em> edges of
	 * the other views in its cell group.
	 */
	public static final Alignment LEFT = LEADING;

	/**
	 * Indicates that a view should be <em>centered</em> with the other views in
	 * its cell group. This constant may be used in both
	 * {@link LayoutParams#rowSpec rowSpecs} and {@link LayoutParams#columnSpec
	 * columnSpecs}.
	 */
	public static final Alignment CENTER = new Alignment() {
		@Override
		public int getAlignmentValue(View view, int viewSize) {
			return viewSize >> 1;
		}
	};

	/**
	 * Indicates that a view should be aligned with the <em>baselines</em> of
	 * the other views in its cell group. This constant may only be used as an
	 * alignment in {@link LayoutParams#rowSpec rowSpecs}.
	 * 
	 * @see View#getBaseline()
	 */
	public static final Alignment BASELINE = new Alignment() {
		@Override
		public int getAlignmentValue(View view, int viewSize) {
			if (view == null) {
				return UNDEFINED;
			}
			int baseline = view.getBaseline();
			return (baseline == -1) ? UNDEFINED : baseline;
		}

		@Override
		public Bounds getBounds() {
			return new Bounds() {
				/*
				 * In a baseline aligned row in which some components define a
				 * baseline and some don't, we need a third variable to properly
				 * account for all the sizes. This tracks the maximum size of
				 * all the components - including those that don't define a
				 * baseline.
				 */
				private int size;

				@Override
				protected int getOffset(View c, Alignment alignment, int size) {
					return max(0, super.getOffset(c, alignment, size));
				}

				@Override
				protected void include(int before, int after) {
					super.include(before, after);
					this.size = max(this.size, before + after);
				}

				@Override
				protected void reset() {
					super.reset();
					this.size = Integer.MIN_VALUE;
				}

				@Override
				protected int size(boolean min) {
					return max(super.size(min), this.size);
				}
			};
		}
	};

	/**
	 * Indicates that a view should expanded to fit the boundaries of its cell
	 * group. This constant may be used in both {@link LayoutParams#rowSpec
	 * rowSpecs} and {@link LayoutParams#columnSpec columnSpecs}.
	 */
	public static final Alignment FILL = new Alignment() {
		@Override
		public int getAlignmentValue(View view, int viewSize) {
			return UNDEFINED;
		}

		@Override
		public int getSizeInCell(View view, int viewSize, int cellSize,
				int measurementType) {
			return cellSize;
		}
	};

	private static final int INFLEXIBLE = 0;

	private static final int CAN_STRETCH = 2;

	@SuppressWarnings("unchecked")
	static <T> T[] append(T[] a, T[] b) {
		T[] result = (T[]) Array.newInstance(a.getClass().getComponentType(),
				a.length + b.length);
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	// Static utility methods

	static boolean canStretch(int flexibility) {
		return (flexibility & CAN_STRETCH) != 0;
	}

	// Logic to avert infinite loops by ensuring that the cells can be placed
	// somewhere.
	private static int clip(Interval minorRange, boolean minorWasDefined,
			int count) {
		int size = minorRange.size();
		if (count == 0) {
			return size;
		}
		int min = minorWasDefined ? min(minorRange.min, count) : 0;
		return min(size, count - min);
	}

	private static void drawRect(Canvas canvas, int x1, int y1, int x2, int y2,
			Paint paint) {
		canvas.drawRect(x1, y1, x2 - 1, y2 - 1, paint);
	}

	private static boolean fits(int[] a, int value, int start, int end) {
		if (end > a.length) {
			return false;
		}
		for (int i = start; i < end; i++) {
			if (a[i] > value) {
				return false;
			}
		}
		return true;
	}

	static Alignment getAlignment(int gravity, boolean horizontal) {
		int mask = horizontal ? HORIZONTAL_GRAVITY_MASK : VERTICAL_GRAVITY_MASK;
		int shift = horizontal ? AXIS_X_SHIFT : AXIS_Y_SHIFT;
		int flags = (gravity & mask) >> shift;
		switch (flags) {
		case (AXIS_SPECIFIED | AXIS_PULL_BEFORE):
			return LEADING;
		case (AXIS_SPECIFIED | AXIS_PULL_AFTER):
			return TRAILING;
		case (AXIS_SPECIFIED | AXIS_PULL_BEFORE | AXIS_PULL_AFTER):
			return FILL;
		case AXIS_SPECIFIED:
			return CENTER;
		default:
			return UNDEFINED_ALIGNMENT;
		}
	}

	static int max2(int[] a, int valueIfEmpty) {
		int result = valueIfEmpty;
		for (int i = 0, N = a.length; i < N; i++) {
			result = Math.max(result, a[i]);
		}
		return result;
	}

	private static void procrusteanFill(int[] a, int start, int end, int value) {
		int length = a.length;
		Arrays.fill(a, Math.min(start, length), Math.min(end, length), value);
	}

	private static void setCellGroup(LayoutParams lp, int row, int rowSpan,
			int col, int colSpan) {
		lp.setRowSpecSpan(new Interval(row, row + rowSpan));
		lp.setColumnSpecSpan(new Interval(col, col + colSpan));
	}

	/**
	 * Return a Spec, {@code spec}, where:
	 * <ul>
	 * <li> {@code spec.span = [start, start + 1]}</li>
	 * </ul>
	 * 
	 * @param start
	 *            the start index
	 */
	public static Spec spec(int start) {
		return spec(start, 1);
	}

	/**
	 * Return a Spec, {@code spec}, where:
	 * <ul>
	 * <li> {@code spec.span = [start, start + 1]}</li>
	 * <li> {@code spec.alignment = alignment}</li>
	 * </ul>
	 * 
	 * @param start
	 *            the start index
	 * @param alignment
	 *            the alignment
	 */
	public static Spec spec(int start, Alignment alignment) {
		return spec(start, 1, alignment);
	}

	/**
	 * Return a Spec, {@code spec}, where:
	 * <ul>
	 * <li> {@code spec.span = [start, start + size]}</li>
	 * </ul>
	 * 
	 * @param start
	 *            the start
	 * @param size
	 *            the size
	 */
	public static Spec spec(int start, int size) {
		return spec(start, size, UNDEFINED_ALIGNMENT);
	}

	/**
	 * Return a Spec, {@code spec}, where:
	 * <ul>
	 * <li> {@code spec.span = [start, start + size]}</li>
	 * <li> {@code spec.alignment = alignment}</li>
	 * </ul>
	 * 
	 * @param start
	 *            the start
	 * @param size
	 *            the size
	 * @param alignment
	 *            the alignment
	 */
	public static Spec spec(int start, int size, Alignment alignment) {
		return new Spec(start != UNDEFINED, start, size, alignment);
	}

	final Axis horizontalAxis = new Axis(true);

	final Axis verticalAxis = new Axis(false);

	boolean layoutParamsValid = false;

	int orientation = DEFAULT_ORIENTATION;

	boolean useDefaultMargins = DEFAULT_USE_DEFAULT_MARGINS;

	int alignmentMode = DEFAULT_ALIGNMENT_MODE;

	int defaultGap;

	private final Paint paint = new Paint();

	private OnHierarchyChangeListener mListener;

	// Draw grid

	private final OnHierarchyChangeListener GRIDLAYOUT_LISTENER = new OnHierarchyChangeListener() {
		@Override
		public void onChildViewAdded(View parent, View child) {
			if (GridLayout.this.mListener != null) {
				GridLayout.this.mListener.onChildViewAdded(parent, child);
			}

			GridLayout.this.invalidateStructure();
		}

		@Override
		public void onChildViewRemoved(View parent, View child) {
			if (GridLayout.this.mListener != null) {
				GridLayout.this.mListener.onChildViewRemoved(parent, child);
			}

			GridLayout.this.invalidateStructure();
		}
	};

	private static boolean mResolveSizeAndStateAvailable;

	static {
		try {
			ResolveSizeAndStateWrapper.checkAvailable();
			mResolveSizeAndStateAvailable = true;
		} catch (Throwable t) {
			mResolveSizeAndStateAvailable = false;
		}
	}

	// Measurement

	/**
	 * {@inheritDoc}
	 */
	public GridLayout(Context context) {
		// noinspection NullableProblems
		this(context, null);
	}

	/**
	 * {@inheritDoc}
	 */
	public GridLayout(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	/**
	 * {@inheritDoc}
	 */
	public GridLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		if (DEBUG) {
			this.setWillNotDraw(false);
		}
		this.defaultGap = context.getResources().getDimensionPixelOffset(
				R.dimen.default_gap);
		TypedArray a = context.obtainStyledAttributes(attrs,
				R.styleable.GridLayout);
		try {
			this.setRowCount(a.getInt(ROW_COUNT, DEFAULT_COUNT));
			this.setColumnCount(a.getInt(COLUMN_COUNT, DEFAULT_COUNT));
			this.setOrientation(a.getInt(ORIENTATION, DEFAULT_ORIENTATION));
			this.setUseDefaultMargins(a.getBoolean(USE_DEFAULT_MARGINS,
					DEFAULT_USE_DEFAULT_MARGINS));
			this.setAlignmentMode(a.getInt(ALIGNMENT_MODE,
					DEFAULT_ALIGNMENT_MODE));
			this.setRowOrderPreserved(a.getBoolean(ROW_ORDER_PRESERVED,
					DEFAULT_ORDER_PRESERVED));
			this.setColumnOrderPreserved(a.getBoolean(COLUMN_ORDER_PRESERVED,
					DEFAULT_ORDER_PRESERVED));
		} finally {
			a.recycle();
		}

		// Set our own custom hierarchy listener, so we can properly implement
		// onViewAdded() and onViewRemoved()
		super.setOnHierarchyChangeListener(this.GRIDLAYOUT_LISTENER);
	}

	private void drawLine(Canvas graphics, int x1, int y1, int x2, int y2,
			Paint paint) {
		int dx = this.getPaddingLeft();
		int dy = this.getPaddingTop();
		graphics.drawLine(dx + x1, dy + y1, dx + x2, dy + y2, paint);
	}

	@Override
	protected LayoutParams generateDefaultLayoutParams() {
		return new LayoutParams();
	}

	@Override
	public LayoutParams generateLayoutParams(AttributeSet attrs) {
		return new LayoutParams(this.getContext(), attrs);
	}

	@Override
	protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
		return new LayoutParams(p);
	}

	final Alignment getAlignment(Alignment alignment, boolean horizontal) {
		return (alignment != UNDEFINED_ALIGNMENT) ? alignment
				: (horizontal ? LEFT : BASELINE);
	}

	/**
	 * Returns the alignment mode.
	 * 
	 * @return the alignment mode; either {@link #ALIGN_BOUNDS} or
	 *         {@link #ALIGN_MARGINS}
	 * 
	 * @see #ALIGN_BOUNDS
	 * @see #ALIGN_MARGINS
	 * 
	 * @see #setAlignmentMode(int)
	 * 
	 * @attr ref android.R.styleable#GridLayout_alignmentMode
	 */
	public int getAlignmentMode() {
		return this.alignmentMode;
	}

	// Layout container

	/**
	 * Returns the current number of columns. This is either the last value that
	 * was set with {@link #setColumnCount(int)} or, if no such value was set,
	 * the maximum value of each the upper bounds defined in
	 * {@link LayoutParams#columnSpec}.
	 * 
	 * @return the current number of columns
	 * 
	 * @see #setColumnCount(int)
	 * @see LayoutParams#columnSpec
	 * 
	 * @attr ref android.R.styleable#GridLayout_columnCount
	 */
	public int getColumnCount() {
		return this.horizontalAxis.getCount();
	}

	// Inner classes

	/** @noinspection UnusedParameters */
	private int getDefaultMargin(View c, boolean horizontal, boolean leading) {
		if (c.getClass() == Space.class) {
			return 0;
		}
		return this.defaultGap / 2;
	}

	private int getDefaultMargin(View c, boolean isAtEdge, boolean horizontal,
			boolean leading) {
		return isAtEdge ? DEFAULT_CONTAINER_MARGIN : this.getDefaultMargin(c,
				horizontal, leading);
	}

	private int getDefaultMarginValue(View c, LayoutParams p,
			boolean horizontal, boolean leading) {
		if (!this.useDefaultMargins) {
			return 0;
		}
		Spec spec = horizontal ? p.columnSpec : p.rowSpec;
		Axis axis = horizontal ? this.horizontalAxis : this.verticalAxis;
		Interval span = spec.span;
		boolean isAtEdge = leading ? (span.min == 0) : (span.max == axis
				.getCount());

		return this.getDefaultMargin(c, isAtEdge, horizontal, leading);
	}

	// A mutable Integer - used to avoid heap allocation during the layout
	// operation

	final LayoutParams getLayoutParams(View c) {
		if (!this.layoutParamsValid) {
			this.validateLayoutParams();
			this.layoutParamsValid = true;
		}
		return this.getLayoutParams1(c);
	}

	private LayoutParams getLayoutParams1(View c) {
		return (LayoutParams) c.getLayoutParams();
	}

	private int getMargin(View view, boolean horizontal, boolean leading) {
		if (this.alignmentMode == ALIGN_MARGINS) {
			return this.getMargin1(view, horizontal, leading);
		} else {
			Axis axis = horizontal ? this.horizontalAxis : this.verticalAxis;
			int[] margins = leading ? axis.getLeadingMargins() : axis
					.getTrailingMargins();
			LayoutParams lp = this.getLayoutParams(view);
			Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
			int index = leading ? spec.span.min : spec.span.max;
			return margins[index];
		}
	}

	int getMargin1(View view, boolean horizontal, boolean leading) {
		LayoutParams lp = this.getLayoutParams(view);
		int margin = horizontal ? (leading ? lp.leftMargin : lp.rightMargin)
				: (leading ? lp.topMargin : lp.bottomMargin);
		return margin == UNDEFINED ? this.getDefaultMarginValue(view, lp,
				horizontal, leading) : margin;
	}

	private int getMeasurement(View c, boolean horizontal) {
		return horizontal ? c.getMeasuredWidth() : c.getMeasuredHeight();
	}

	final int getMeasurementIncludingMargin(View c, boolean horizontal) {
		if (this.isGone(c)) {
			return 0;
		}
		return this.getMeasurement(c, horizontal)
				+ this.getTotalMargin(c, horizontal);
	}

	/**
	 * Returns the current orientation.
	 * 
	 * @return either {@link #HORIZONTAL} or {@link #VERTICAL}
	 * 
	 * @see #setOrientation(int)
	 * 
	 * @attr ref android.R.styleable#GridLayout_orientation
	 */
	public int getOrientation() {
		return this.orientation;
	}

	/**
	 * Returns the current number of rows. This is either the last value that
	 * was set with {@link #setRowCount(int)} or, if no such value was set, the
	 * maximum value of each the upper bounds defined in
	 * {@link LayoutParams#rowSpec}.
	 * 
	 * @return the current number of rows
	 * 
	 * @see #setRowCount(int)
	 * @see LayoutParams#rowSpec
	 * 
	 * @attr ref android.R.styleable#GridLayout_rowCount
	 */
	public int getRowCount() {
		return this.verticalAxis.getCount();
	}

	private int getTotalMargin(View child, boolean horizontal) {
		return this.getMargin(child, horizontal, true)
				+ this.getMargin(child, horizontal, false);
	}

	/**
	 * Returns whether or not this GridLayout will allocate default margins when
	 * no corresponding layout parameters are defined.
	 * 
	 * @return {@code true} if default margins should be allocated
	 * 
	 * @see #setUseDefaultMargins(boolean)
	 * 
	 * @attr ref android.R.styleable#GridLayout_useDefaultMargins
	 */
	public boolean getUseDefaultMargins() {
		return this.useDefaultMargins;
	}

	private void invalidateStructure() {
		this.layoutParamsValid = false;
		this.horizontalAxis.invalidateStructure();
		this.verticalAxis.invalidateStructure();
		// This can end up being done twice. Better twice than not at all.
		this.invalidateValues();
	}

	private void invalidateValues() {
		// Need null check because requestLayout() is called in View's
		// initializer,
		// before we are set up.
		if (this.horizontalAxis != null && this.verticalAxis != null) {
			this.horizontalAxis.invalidateValues();
			this.verticalAxis.invalidateValues();
		}
	}

	/**
	 * Returns whether or not column boundaries are ordered by their grid
	 * indices.
	 * 
	 * @return {@code true} if column boundaries must appear in the order of
	 *         their indices, {@code false} otherwise
	 * 
	 * @see #setColumnOrderPreserved(boolean)
	 * 
	 * @attr ref android.R.styleable#GridLayout_columnOrderPreserved
	 */
	public boolean isColumnOrderPreserved() {
		return this.horizontalAxis.isOrderPreserved();
	}

	final boolean isGone(View c) {
		return c.getVisibility() == View.GONE;
	}

	/**
	 * Returns whether or not row boundaries are ordered by their grid indices.
	 * 
	 * @return {@code true} if row boundaries must appear in the order of their
	 *         indices, {@code false} otherwise
	 * 
	 * @see #setRowOrderPreserved(boolean)
	 * 
	 * @attr ref android.R.styleable#GridLayout_rowOrderPreserved
	 */
	public boolean isRowOrderPreserved() {
		return this.verticalAxis.isOrderPreserved();
	}

	private void measureChildrenWithMargins(int widthSpec, int heightSpec,
			boolean firstPass) {
		for (int i = 0, N = this.getChildCount(); i < N; i++) {
			View c = this.getChildAt(i);
			if (this.isGone(c))
				continue;
			LayoutParams lp = this.getLayoutParams(c);
			if (firstPass) {
				this.measureChildWithMargins2(c, widthSpec, heightSpec,
						lp.width, lp.height);
			} else {
				boolean horizontal = (this.orientation == HORIZONTAL);
				Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
				if (spec.alignment == FILL) {
					Interval span = spec.span;
					Axis axis = horizontal ? this.horizontalAxis
							: this.verticalAxis;
					int[] locations = axis.getLocations();
					int cellSize = locations[span.max] - locations[span.min];
					int viewSize = cellSize
							- this.getTotalMargin(c, horizontal);
					if (horizontal) {
						this.measureChildWithMargins2(c, widthSpec, heightSpec,
								viewSize, lp.height);
					} else {
						this.measureChildWithMargins2(c, widthSpec, heightSpec,
								lp.width, viewSize);
					}
				}
			}
		}
	}

	private void measureChildWithMargins2(View child, int parentWidthSpec,
			int parentHeightSpec, int childWidth, int childHeight) {
		int childWidthSpec = getChildMeasureSpec(
				parentWidthSpec,
				this.getPaddingLeft() + this.getPaddingRight()
						+ this.getTotalMargin(child, true), childWidth);
		int childHeightSpec = getChildMeasureSpec(
				parentHeightSpec,
				this.getPaddingTop() + this.getPaddingBottom()
						+ this.getTotalMargin(child, false), childHeight);
		child.measure(childWidthSpec, childHeightSpec);
	}

	public void notifyChildVisibilityChanged() {
		this.invalidateStructure();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		if (DEBUG) {
			int height = this.getHeight() - this.getPaddingTop()
					- this.getPaddingBottom();
			int width = this.getWidth() - this.getPaddingLeft()
					- this.getPaddingRight();

			this.paint.setStyle(Paint.Style.STROKE);
			this.paint.setColor(Color.argb(50, 255, 255, 255));

			int[] xs = this.horizontalAxis.locations;
			if (xs != null) {
				for (int i = 0, length = xs.length; i < length; i++) {
					int x = xs[i];
					this.drawLine(canvas, x, 0, x, height - 1, this.paint);
				}
			}

			int[] ys = this.verticalAxis.locations;
			if (ys != null) {
				for (int i = 0, length = ys.length; i < length; i++) {
					int y = ys[i];
					this.drawLine(canvas, 0, y, width - 1, y, this.paint);
				}
			}

			// Draw bounds
			this.paint.setColor(Color.BLUE);
			for (int i = 0; i < this.getChildCount(); i++) {
				View c = this.getChildAt(i);
				drawRect(canvas, c.getLeft(), c.getTop(), c.getRight(),
						c.getBottom(), this.paint);
			}

			// Draw margins
			this.paint.setColor(Color.MAGENTA);
			for (int i = 0; i < this.getChildCount(); i++) {
				View c = this.getChildAt(i);
				drawRect(canvas, c.getLeft() - this.getMargin1(c, true, true),
						c.getTop() - this.getMargin1(c, false, true),
						c.getRight() + this.getMargin1(c, true, false),
						c.getBottom() + this.getMargin1(c, false, false),
						this.paint);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	/*
	 * The layout operation is implemented by delegating the heavy lifting to
	 * the to the mHorizontalAxis and mVerticalAxis instances of the internal
	 * Axis class. Together they compute the locations of the vertical and
	 * horizontal lines of the grid (respectively!).
	 * 
	 * This method is then left with the simpler task of applying margins,
	 * gravity and sizing to each child view and then placing it in its cell.
	 */
	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		int targetWidth = right - left;
		int targetHeight = bottom - top;

		int paddingLeft = this.getPaddingLeft();
		int paddingTop = this.getPaddingTop();
		int paddingRight = this.getPaddingRight();
		int paddingBottom = this.getPaddingBottom();

		this.horizontalAxis.layout(targetWidth - paddingLeft - paddingRight);
		this.verticalAxis.layout(targetHeight - paddingTop - paddingBottom);

		int[] hLocations = this.horizontalAxis.getLocations();
		int[] vLocations = this.verticalAxis.getLocations();

		for (int i = 0, N = this.getChildCount(); i < N; i++) {
			View c = this.getChildAt(i);
			if (this.isGone(c))
				continue;
			LayoutParams lp = this.getLayoutParams(c);
			Spec columnSpec = lp.columnSpec;
			Spec rowSpec = lp.rowSpec;

			Interval colSpan = columnSpec.span;
			Interval rowSpan = rowSpec.span;

			int x1 = hLocations[colSpan.min];
			int y1 = vLocations[rowSpan.min];

			int x2 = hLocations[colSpan.max];
			int y2 = vLocations[rowSpan.max];

			int cellWidth = x2 - x1;
			int cellHeight = y2 - y1;

			int pWidth = this.getMeasurement(c, true);
			int pHeight = this.getMeasurement(c, false);

			Alignment hAlign = this.getAlignment(columnSpec.alignment, true);
			Alignment vAlign = this.getAlignment(rowSpec.alignment, false);

			int dx, dy;

			Bounds colBounds = this.horizontalAxis.getGroupBounds().getValue(i);
			Bounds rowBounds = this.verticalAxis.getGroupBounds().getValue(i);

			// Gravity offsets: the location of the alignment group relative to
			// its cell group.
			// noinspection NullableProblems
			int c2ax = this.protect(hAlign.getAlignmentValue(null, cellWidth
					- colBounds.size(true)));
			// noinspection NullableProblems
			int c2ay = this.protect(vAlign.getAlignmentValue(null, cellHeight
					- rowBounds.size(true)));

			int leftMargin = this.getMargin(c, true, true);
			int topMargin = this.getMargin(c, false, true);
			int rightMargin = this.getMargin(c, true, false);
			int bottomMargin = this.getMargin(c, false, false);

			// Same calculation as getMeasurementIncludingMargin()
			int mWidth = leftMargin + pWidth + rightMargin;
			int mHeight = topMargin + pHeight + bottomMargin;

			// Alignment offsets: the location of the view relative to its
			// alignment group.
			int a2vx = colBounds.getOffset(c, hAlign, mWidth);
			int a2vy = rowBounds.getOffset(c, vAlign, mHeight);

			dx = c2ax + a2vx + leftMargin;
			dy = c2ay + a2vy + topMargin;

			cellWidth -= leftMargin + rightMargin;
			cellHeight -= topMargin + bottomMargin;

			int type = PRF;
			int width = hAlign.getSizeInCell(c, pWidth, cellWidth, type);
			int height = vAlign.getSizeInCell(c, pHeight, cellHeight, type);

			int cx = paddingLeft + x1 + dx;
			int cy = paddingTop + y1 + dy;
			if (width != c.getMeasuredWidth()
					|| height != c.getMeasuredHeight()) {
				c.measure(makeMeasureSpec(width, EXACTLY),
						makeMeasureSpec(height, EXACTLY));
			}
			c.layout(cx, cy, cx + width, cy + height);
		}
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		/**
		 * If we have been called by {@link View#measure(int, int)}, one of
		 * width or height is likely to have changed. We must invalidate if so.
		 */
		this.invalidateValues();

		this.measureChildrenWithMargins(widthSpec, heightSpec, true);

		int width, height;

		// Use the orientation property to decide which axis should be laid out
		// first.
		if (this.orientation == HORIZONTAL) {
			width = this.horizontalAxis.getMeasure(widthSpec);
			this.measureChildrenWithMargins(widthSpec, heightSpec, false);
			height = this.verticalAxis.getMeasure(heightSpec);
		} else {
			height = this.verticalAxis.getMeasure(heightSpec);
			this.measureChildrenWithMargins(widthSpec, heightSpec, false);
			width = this.horizontalAxis.getMeasure(widthSpec);
		}

		int hPadding = this.getPaddingLeft() + this.getPaddingRight();
		int vPadding = this.getPaddingTop() + this.getPaddingBottom();

		int measuredWidth = Math.max(hPadding + width,
				this.getSuggestedMinimumWidth());
		int measuredHeight = Math.max(vPadding + height,
				this.getSuggestedMinimumHeight());

		if (mResolveSizeAndStateAvailable) {
			measuredWidth = ResolveSizeAndStateWrapper.resolveSizeAndState(
					measuredWidth, widthSpec, 0);
			measuredHeight = ResolveSizeAndStateWrapper.resolveSizeAndState(
					measuredHeight, heightSpec, 0);
		} else {
			measuredWidth = resolveSize(measuredWidth, widthSpec);
			measuredHeight = resolveSize(measuredHeight, heightSpec);
		}

		this.setMeasuredDimension(measuredWidth, measuredHeight);
	}

	private int protect(int alignment) {
		return (alignment == UNDEFINED) ? 0 : alignment;
	}

	@Override
	public void requestLayout() {
		super.requestLayout();
		this.invalidateValues();
	}

	/**
	 * Sets the alignment mode to be used for all of the alignments between the
	 * children of this container.
	 * <p>
	 * The default value of this property is {@link #ALIGN_MARGINS}.
	 * 
	 * @param alignmentMode
	 *            either {@link #ALIGN_BOUNDS} or {@link #ALIGN_MARGINS}
	 * 
	 * @see #ALIGN_BOUNDS
	 * @see #ALIGN_MARGINS
	 * 
	 * @see #getAlignmentMode()
	 * 
	 * @attr ref android.R.styleable#GridLayout_alignmentMode
	 */
	public void setAlignmentMode(int alignmentMode) {
		this.alignmentMode = alignmentMode;
		this.requestLayout();
	}

	// ////////////////////////////////////////////////////////////////////////
	// Pair
	//
	// Older versions of Android do not have this class, so here's a local
	// version for backwards compatibility

	/**
	 * ColumnCount is used only to generate default column/column indices when
	 * they are not specified by a component's layout parameters.
	 * 
	 * @param columnCount
	 *            the number of columns.
	 * 
	 * @see #getColumnCount()
	 * @see LayoutParams#columnSpec
	 * 
	 * @attr ref android.R.styleable#GridLayout_columnCount
	 */
	public void setColumnCount(int columnCount) {
		this.horizontalAxis.setCount(columnCount);
		this.invalidateStructure();
		this.requestLayout();
	}

	// ////////////////////////////////////////////////////////////////////////
	// Wrapped OnHierarchyChangeListener
	//
	// We need to listen to hierarchy changes ourselves, so we set our own
	// listener in the constructor then handle setting of a custom listener
	// for anyone else who wants to consume these events.

	/**
	 * When this property is {@code true}, GridLayout is forced to place the
	 * column boundaries so that their associated grid indices are in ascending
	 * order in the view.
	 * <p>
	 * When this property is {@code false} GridLayout is at liberty to place the
	 * horizontal column boundaries in whatever order best fits the given
	 * constraints.
	 * <p>
	 * The default value of this property is {@code true}.
	 * 
	 * @param columnOrderPreserved
	 *            use {@code true} to force GridLayout to respect the order of
	 *            column boundaries.
	 * 
	 * @see #isColumnOrderPreserved()
	 * 
	 * @attr ref android.R.styleable#GridLayout_columnOrderPreserved
	 */
	public void setColumnOrderPreserved(boolean columnOrderPreserved) {
		this.horizontalAxis.setOrderPreserved(columnOrderPreserved);
		this.invalidateStructure();
		this.requestLayout();
	}

	@Override
	public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
		this.mListener = listener;
	}

	/**
	 * Orientation is used only to generate default row/column indices when they
	 * are not specified by a component's layout parameters.
	 * <p>
	 * The default value of this property is {@link #HORIZONTAL}.
	 * 
	 * @param orientation
	 *            either {@link #HORIZONTAL} or {@link #VERTICAL}
	 * 
	 * @see #getOrientation()
	 * 
	 * @attr ref android.R.styleable#GridLayout_orientation
	 */
	public void setOrientation(int orientation) {
		if (this.orientation != orientation) {
			this.orientation = orientation;
			this.invalidateStructure();
			this.requestLayout();
		}
	}

	// ////////////////////////////////////////////////////////////////////////
	// resolveSizeAndState() wrapper
	//
	// This newer method of measurement was introduced in API 11, so we
	// conditionally use it if available.

	/**
	 * RowCount is used only to generate default row/column indices when they
	 * are not specified by a component's layout parameters.
	 * 
	 * @param rowCount
	 *            the number of rows
	 * 
	 * @see #getRowCount()
	 * @see LayoutParams#rowSpec
	 * 
	 * @attr ref android.R.styleable#GridLayout_rowCount
	 */
	public void setRowCount(int rowCount) {
		this.verticalAxis.setCount(rowCount);
		this.invalidateStructure();
		this.requestLayout();
	}

	/**
	 * When this property is {@code true}, GridLayout is forced to place the row
	 * boundaries so that their associated grid indices are in ascending order
	 * in the view.
	 * <p>
	 * When this property is {@code false} GridLayout is at liberty to place the
	 * vertical row boundaries in whatever order best fits the given
	 * constraints.
	 * <p>
	 * The default value of this property is {@code true}.
	 * 
	 * @param rowOrderPreserved
	 *            {@code true} to force GridLayout to respect the order of row
	 *            boundaries
	 * 
	 * @see #isRowOrderPreserved()
	 * 
	 * @attr ref android.R.styleable#GridLayout_rowOrderPreserved
	 */
	public void setRowOrderPreserved(boolean rowOrderPreserved) {
		this.verticalAxis.setOrderPreserved(rowOrderPreserved);
		this.invalidateStructure();
		this.requestLayout();
	}

	/**
	 * When {@code true}, GridLayout allocates default margins around children
	 * based on the child's visual characteristics. Each of the margins so
	 * defined may be independently overridden by an assignment to the
	 * appropriate layout parameter.
	 * <p>
	 * When {@code false}, the default value of all margins is zero.
	 * <p>
	 * When setting to {@code true}, consider setting the value of the
	 * {@link #setAlignmentMode(int) alignmentMode} property to
	 * {@link #ALIGN_BOUNDS}.
	 * <p>
	 * The default value of this property is {@code false}.
	 * 
	 * @param useDefaultMargins
	 *            use {@code true} to make GridLayout allocate default margins
	 * 
	 * @see #getUseDefaultMargins()
	 * @see #setAlignmentMode(int)
	 * 
	 * @see MarginLayoutParams#leftMargin
	 * @see MarginLayoutParams#topMargin
	 * @see MarginLayoutParams#rightMargin
	 * @see MarginLayoutParams#bottomMargin
	 * 
	 * @attr ref android.R.styleable#GridLayout_useDefaultMargins
	 */
	public void setUseDefaultMargins(boolean useDefaultMargins) {
		this.useDefaultMargins = useDefaultMargins;
		this.requestLayout();
	}

	// ////////////////////////////////////////////////////////////////////////
	// Notifications of child visibility changes
	//
	// We need to call invalidateStructure() when a child's GONE flag changes
	// state. However, the API 14's implementation depends on
	// ViewGroup.onChildVisibilityChanged(), which is nonexistant in older
	// versions of Android. As a compromise, the method below should be
	// called whenever the visibility of children change.

	// install default indices for cells that don't define them
	private void validateLayoutParams() {
		final boolean horizontal = (this.orientation == HORIZONTAL);
		final Axis axis = horizontal ? this.horizontalAxis : this.verticalAxis;
		final int count = (axis.definedCount != UNDEFINED) ? axis.definedCount
				: 0;

		int major = 0;
		int minor = 0;
		int[] maxSizes = new int[count];

		for (int i = 0, N = this.getChildCount(); i < N; i++) {
			LayoutParams lp = this.getLayoutParams1(this.getChildAt(i));

			final Spec majorSpec = horizontal ? lp.rowSpec : lp.columnSpec;
			final Interval majorRange = majorSpec.span;
			final boolean majorWasDefined = majorSpec.startDefined;
			final int majorSpan = majorRange.size();
			if (majorWasDefined) {
				major = majorRange.min;
			}

			final Spec minorSpec = horizontal ? lp.columnSpec : lp.rowSpec;
			final Interval minorRange = minorSpec.span;
			final boolean minorWasDefined = minorSpec.startDefined;
			final int minorSpan = clip(minorRange, minorWasDefined, count);
			if (minorWasDefined) {
				minor = minorRange.min;
			}

			if (count != 0) {
				// Find suitable row/col values when at least one is undefined.
				if (!majorWasDefined || !minorWasDefined) {
					while (!fits(maxSizes, major, minor, minor + minorSpan)) {
						if (minorWasDefined) {
							major++;
						} else {
							if (minor + minorSpan <= count) {
								minor++;
							} else {
								minor = 0;
								major++;
							}
						}
					}
				}
				procrusteanFill(maxSizes, minor, minor + minorSpan, major
						+ majorSpan);
			}

			if (horizontal) {
				setCellGroup(lp, major, majorSpan, minor, minorSpan);
			} else {
				setCellGroup(lp, minor, minorSpan, major, majorSpan);
			}

			minor = minor + minorSpan;
		}
		this.invalidateStructure();
	}
}
