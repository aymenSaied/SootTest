/* NullnessAnalysis
 * Copyright (C) 2006 Eric Bodden
 * Copyright (C) 2007 Julian Tibble
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package NullOrNotNullParamUsingModifiedNullnessAnalysis;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import soot.Immediate;
import soot.Local;
import soot.RefLikeType;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.ClassConstant;
import soot.jimple.DefinitionStmt;
import soot.jimple.FieldRef;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.MonitorStmt;
import soot.jimple.NewArrayExpr;
import soot.jimple.NewExpr;
import soot.jimple.NewMultiArrayExpr;
import soot.jimple.NullConstant;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.ThisRef;
import soot.jimple.internal.AbstractBinopExpr;
import soot.jimple.internal.JCastExpr;
import soot.jimple.internal.JEqExpr;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInstanceOfExpr;
import soot.jimple.internal.JNeExpr;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.ForwardBranchedFlowAnalysis;


/**
 * An intraprocedural nullness analysis that computes for each location and each value
 * in a method if the value is (before or after that location) definetely null,
 * definetely non-null or neither.
 * This class replaces {@link BranchedRefVarsAnalysis} which is known to have bugs.
 *
 * @author Eric Bodden
 * @author Julian Tibble
 */
public class ModifiedNullnessAnalysis  extends ForwardBranchedFlowAnalysis
{
	/**
	 * The analysis info is a simple mapping of type {@link Value} to
	 * any of the constants BOTTOM, NON_NULL, NULL or TOP.
	 * This class returns BOTTOM by default.
	 * 
	 * @author Julian Tibble
	 */
	protected class AnalysisInfo extends java.util.BitSet
	{
		public AnalysisInfo() {
			super(used);
		}

		public AnalysisInfo(AnalysisInfo other) {
			super(used);
			or(other);
		}

		public int get(Value key)
		{
			if (!valueToIndex.containsKey(key))
				return BOTTOM;

			int index = valueToIndex.get(key);
			int result = get(index) ? 2 : 0;
			result += get(index + 1) ? 1 : 0;

			return result;
		}
		
		public void put(Value key, int val)
		{
			int index;
			if (!valueToIndex.containsKey(key)) {
				index = used;
				used += 2;
				valueToIndex.put(key, index);
			} else {
				index = valueToIndex.get(key);
			}
			set(index, (val & 2) == 2);
			set(index + 1, (val & 1) == 1);
		}
	}

	protected final static int BOTTOM = 0;
	protected final static int NULL = 1;
	protected final static int NON_NULL = 2;
	protected final static int TOP = 3;
	
	protected final HashMap<Value,Integer> valueToIndex = new HashMap<Value,Integer>();
	protected int used = 0;

	/**
	 * Creates a new analysis for the given graph/
	 * @param graph any unit graph
	 */
	public ModifiedNullnessAnalysis(UnitGraph graph) {
		super(graph);
		
		doAnalysis();
	}

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	protected void flowThrough(Object flowin, Unit u, List fallOut, List branchOuts) {
		AnalysisInfo in = (AnalysisInfo) flowin;
		AnalysisInfo out = new AnalysisInfo(in);
		AnalysisInfo outBranch = new AnalysisInfo(in);
		
		Stmt s = (Stmt)u;
		
		//in case of an if statement, we neet to compute the branch-flow;
		//e.g. for a statement "if(x!=null) goto s" we have x==null for the fallOut and
		//x!=null for the branchOut
		//or for an instanceof expression
		if(s instanceof JIfStmt) {
			JIfStmt ifStmt = (JIfStmt) s;
			handleIfStmt(ifStmt, in, out, outBranch);
		}
		
		
		
		//if we have a definition (assignment) statement to a ref-like type, handle it,
		//i.e. assign it TOP, except in the following special cases:
		// x=null,               assign NULL
		// x=@this or x= new...  assign NON_NULL
		// x=y,                  copy the info for y (for locals x,y)
		if(s instanceof DefinitionStmt) {
			DefinitionStmt defStmt = (DefinitionStmt) s;
			if(defStmt.getLeftOp().getType() instanceof RefLikeType) {
				handleRefTypeAssignment(defStmt, out);
			}
		}
		
		// now copy the computed info to all successors
		for( Iterator it = fallOut.iterator(); it.hasNext(); ) {
			copy( out, it.next() );
		}
		for( Iterator it = branchOuts.iterator(); it.hasNext(); ) {
			copy( outBranch, it.next() );
		}
	}
	
	/**
	 * This can be overwritten by sublasses to mark a certain value
	 * as constantly non-null.
	 * @param v any value
	 * @return true if it is known that this value (e.g. a method
	 * return value) is never null
	 */
	protected boolean isAlwaysNonNull(Value v) {
		return false;
	}
	
	private void handleIfStmt(JIfStmt ifStmt, AnalysisInfo in, AnalysisInfo out, AnalysisInfo outBranch) {
		Value condition = ifStmt.getCondition();
		if(condition instanceof JInstanceOfExpr) {
			//a instanceof X ; if this succeeds, a is not null
			JInstanceOfExpr expr = (JInstanceOfExpr) condition;
			handleInstanceOfExpression(expr, in, out, outBranch);
		} else if(condition instanceof JEqExpr || condition instanceof JNeExpr) {
			//a==b or a!=b
			AbstractBinopExpr eqExpr = (AbstractBinopExpr) condition;
			handleEqualityOrNonEqualityCheck(eqExpr, in, out, outBranch);
		} 		
	}

	private void handleEqualityOrNonEqualityCheck(AbstractBinopExpr eqExpr, AnalysisInfo in,
			AnalysisInfo out, AnalysisInfo outBranch) {
		Value left = eqExpr.getOp1();
		Value right = eqExpr.getOp2();
		
		Value val=null;
		if(left==NullConstant.v()) {
			if(right!=NullConstant.v()) {
				val = right;
			}
		} else if(right==NullConstant.v()) {
			if(left!=NullConstant.v()) {
				val = left;
			}
		}
		
		//if we compare a local with null then process further...
		if(val!=null && val instanceof Local) {
			if(eqExpr instanceof JEqExpr)
				//a==null
				handleEquality(val, out, outBranch);
			else if(eqExpr instanceof JNeExpr)
				//a!=null
				handleNonEquality(val, out, outBranch);
			else
				throw new IllegalStateException("unexpected condition: "+eqExpr.getClass());
		}
	}

	private void handleNonEquality(Value val, AnalysisInfo out,
			AnalysisInfo outBranch) {
		out.put(val, NULL);
		outBranch.put(val, NON_NULL);
	}

	private void handleEquality(Value val, AnalysisInfo out,
			AnalysisInfo outBranch) {
		out.put(val, NON_NULL);
		outBranch.put(val, NULL);
	}
	
	private void handleInstanceOfExpression(JInstanceOfExpr expr,
			AnalysisInfo in, AnalysisInfo out, AnalysisInfo outBranch) {
		Value op = expr.getOp();
		//if instanceof succeeds, we have a non-null value
		outBranch.put(op,NON_NULL);
	}


	

	

	private void handleRefTypeAssignment(DefinitionStmt assignStmt, AnalysisInfo out) {
		Value left = assignStmt.getLeftOp();
		Value right = assignStmt.getRightOp();
		
		//unbox casted value
		if(right instanceof JCastExpr) {
			JCastExpr castExpr = (JCastExpr) right;
			right = castExpr.getOp();
		}
		
		//if we have a definition (assignment) statement to a ref-like type, handle it,
		if ( isAlwaysNonNull(right)
		|| right instanceof NewExpr || right instanceof NewArrayExpr
		|| right instanceof NewMultiArrayExpr || right instanceof ThisRef
		|| right instanceof StringConstant || right instanceof ClassConstant) {
			//if we assign new... or @this, the result is non-null
			out.put(left,NON_NULL);
		} else if(right==NullConstant.v()) {
			//if we assign null, well, it's null
			out.put(left, NULL);

			//TODO instruction out.put(left, NULL); dois  est enlever car si on affecte null a une paramaitre ce plus le paramaitre mais une autre variable  	
			
		
		
		} else if(left instanceof Local && right instanceof Local) {
			out.put(left, out.get(right));
		} else {
			out.put(left, BOTTOM);
			//out.put(left, TOP);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	protected void copy(Object source, Object dest) {
		AnalysisInfo s = (AnalysisInfo) source;
		AnalysisInfo d = (AnalysisInfo) dest;
		d.clear();
		d.or(s);
	}

	/**
	 * {@inheritDoc}
	 */
	protected Object entryInitialFlow() {
		return new AnalysisInfo();
	}

	/**
	 * {@inheritDoc}
	 */
	protected void merge(Object in1, Object in2, Object out) {
		AnalysisInfo outflow = (AnalysisInfo) out;
		outflow.clear();
		outflow.or((AnalysisInfo) in1);
		outflow.or((AnalysisInfo) in2);
	}

	/**
	 * {@inheritDoc}
	 */
	protected Object newInitialFlow() {
		return new AnalysisInfo();
	}
	
	/**
	 * Returns <code>true</code> if the analysis could determine that i is always null
	 * before the statement s.
	 * @param s a statement of the respective body
	 * @param i a local or constant of that body
	 * @return true if i is always null right before this statement
	 */
	public boolean isAlwaysNullBefore(Unit s, Immediate i) {
		AnalysisInfo ai = (AnalysisInfo) getFlowBefore(s);
		return ((ai.get(i)==NULL)||(ai.get(i)==TOP) );
		
		//top
	}

	
	public boolean isStrictlyAlwaysNullBefore(Unit s, Immediate i) {
		AnalysisInfo ai = (AnalysisInfo) getFlowBefore(s);
		return ((ai.get(i)==NULL) );
		
		//top
	}
	
	/**
	 * Returns <code>true</code> if the analysis could determine that i is always non-null
	 * before the statement s.
	 * @param s a statement of the respective body
	 * @param i a local of that body
	 * @return true if i is always non-null right before this statement
	 */
	public boolean isAlwaysNonNullBefore(Unit s, Immediate i) {
		AnalysisInfo ai = (AnalysisInfo) getFlowBefore(s);
		System.out.println("DDDD"+"Unit :  "+s+"value :  "+"  ai.get(i) : "+ai.get(i) +"  ai.get(i)==NON_NULL   :   "+ (ai.get(i)==NON_NULL));
		return ai.get(i)==NON_NULL;
		
		
	}
}