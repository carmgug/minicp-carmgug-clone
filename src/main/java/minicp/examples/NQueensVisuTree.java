/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 */

package minicp.examples;

import minicp.cp.Factory;
import minicp.engine.core.IntVar;
import minicp.engine.core.Solver;
import minicp.search.DFSearch;
import minicp.search.SearchStatistics;
import minicp.search.TreeVisualizer;
import minicp.util.Procedure;

import java.util.Arrays;

/**
 * The N-Queens problem.
 * <a href="http://csplib.org/Problems/prob054/">CSPLib</a>.
 */
public class NQueensVisuTree {
    public static void main(String[] args) {
        int n = 8;
        Solver cp = Factory.makeSolver(false);
        IntVar[] q = Factory.makeIntVarArray(cp, n, n);


        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                cp.post(Factory.notEqual(q[i], q[j]));
                cp.post(Factory.notEqual(q[i], q[j], j - i));
                cp.post(Factory.notEqual(q[i], q[j], i - j));


            }
        }

        DFSearch search = Factory.makeDfs(cp, () -> {
            int idx = -1; // index of the first variable that is not fixed
            for (int k = 0; k < q.length; k++)
                if (q[k].size() > 1) {
                    idx = k;
                    break;
                }
            if (idx == -1)
                return new Procedure[0];
            else {
                IntVar qi = q[idx];
                int v = qi.min();
                Procedure left = () -> cp.post(Factory.equal(qi, v));
                Procedure right = () -> cp.post(Factory.notEqual(qi, v));
                return new Procedure[]{left, right};
            }
        });

        TreeVisualizer.cpProfiler(search,() -> Arrays.toString(q));

        search.onSolution(() ->
                System.out.println("solution:" + Arrays.toString(q))
        );
        SearchStatistics stats = search.solve(statistics -> statistics.numberOfSolutions() == 1000);

        System.out.format("#Solutions: %s\n", stats.numberOfSolutions());
        System.out.format("Statistics: %s\n", stats);

    }
}
