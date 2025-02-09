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

package tinycsp.examples;



import tinycsp.TinyCSP;
import tinycsp.Variable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Example that illustrates how TinyCSP can be used to model
 * and solve the graph-coloring problem.
 */
public class GraphColoringTinyCSP {

    public static class GraphColoringInstance {

        public final int n;
        public final List<int []> edges;
        public final int maxColor;

        /**
         *
         * @param n the number of nodes with indices on {0,...,n-1}
         * @param edges a list of edges, an edge (a,b) encoded in a size two array [a,b]
         * @param maxColor, the maximum number of colors allowed in the solution, the allowed colors are {0...maxColor-1}
         */
        public GraphColoringInstance(int n, List<int []> edges, int maxColor) {
            this.n = n;
            this.edges = edges;
            this.maxColor = maxColor;
        }
    }


    public static void main(String[] args) {
        String path = "data/graph_coloring/gc_15_30_9";
        GraphColoringInstance instance = readInstance(path);
        int [] solution= solve(instance);
        writeSol(path+".sol",solution,instance.maxColor);
    }

    /**
     * Useful if you want to visualize your solution
     * @param file where you want to store the solution
     * @param sol the color of each vertex
     * @param nCol the number of colors used
     */
    public static void writeSol(String file, int [] sol, int nCol) {
        try {
            FileWriter fw = new FileWriter(file+".sol");
            fw.write(nCol+" "+1+"\n");

            for (int i = 0; i < sol.length; i++) {
                fw.write(sol[i]+" ");
            }

            fw.write("\n");
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Read the instance at the specified path
     * @param file the path to the instance file
     * @return the instance
     */
    public static GraphColoringInstance  readInstance(String file) {

        Scanner scanner = null;
        try {
            scanner = new Scanner(new File(file));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        int n = scanner.nextInt();
        int e = scanner.nextInt();
        int nCol = scanner.nextInt();

        List<int []> edges = new LinkedList<>();

        for (int i = 0; i < e; i++) {
            int source = scanner.nextInt();
            int dest = scanner.nextInt();
            edges.add(new int[] {source, dest});
        }
        return new GraphColoringInstance(n,edges,nCol);

    }

    /**
     * Solve the graph coloring problem
     * @param instance a graph coloring instance
     * @return the color of each node such that no two adjacent nodes receive a same color,
     *         or null if the problem is unfeasible
     */
    public static int[] solve(GraphColoringInstance instance) {
        TinyCSP csp= new TinyCSP();

        //get the num of vertices of the problem
        int num_vertices=instance.n;

        //Initialize the variables for solving the problem
        Variable[] v = new Variable[num_vertices];
        for(int i=0;i<num_vertices;i++){
            v[i]=csp.makeVariable(instance.maxColor);
        }

        //set constraint
        for (int[] edge : instance.edges){//edge[0]=vertex_1 ; edge[1]=vertex=2

            int vertex_1=edge[0];
            int vertex_2=edge[1];
            //vertex_1 (edge[0]) cannot have the same color as vertex_2 (edge[1])
            //because they are two nodes connected by an edge
            csp.notEqual(v[vertex_1],v[vertex_2],0);
        }
        ArrayList<int []> solutions = new ArrayList<>();

        try {
            csp.dfs(solution -> {
                solutions.add(solution);
                throw new FirstSolution(); //I found the solution so stop it
            });
        }catch (FirstSolution ignored){
        }

        return solutions.get(0);
    }

    static class FirstSolution extends RuntimeException {
    }


}
