package minicp.examples;

import minicp.cp.BranchingScheme;
import minicp.engine.constraints.*;
import minicp.engine.core.BoolVar;
import minicp.engine.core.IntVar;
import minicp.engine.core.Solver;
import minicp.search.DFSearch;
import minicp.search.Objective;
import minicp.search.SearchStatistics;
import minicp.util.exception.InconsistencyException;
import minicp.util.io.InputReader;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static minicp.cp.BranchingScheme.*;
import static minicp.cp.Factory.*;


public class DialARide {

    final int nVehicles;
    final int maxRouteDuration;
    final int vehicleCapacity;
    final int maxRideTime;
    final ArrayList<RideStop> pickupRideStops;
    final ArrayList<RideStop> dropRideStops;
    final RideStop depot;
    final int[][] distanceMatrix;
    final ArrayList<RideStop> pickupAndDropRideStops;
    final int number_of_start_depots; //A depot for each vehicle
    final int number_of_end_depots; // A end depot for each vehicles
    final int number_of_tasks;
    final int n ;
    final int first_end_depot ;

    private Solver cp;
    private IntVar[] succ;
    private IntVar[] pred;
    private IntVar[] distSucc;
    private IntVar[] time;
    private IntVar[] peopleOn;
    private IntVar[] index;
    private IntVar[] visitedByVehicle;

    //i is the task
    private IntVar[] managedBy;

    private IntVar totalDist;
    private Objective obj_function;





    public DialARide (int nVehicles, int maxRouteDuration, int vehicleCapacity,
                      int maxRideTime, ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,
                      RideStop depot) {
        this.nVehicles = nVehicles;
        this.maxRouteDuration = maxRouteDuration;
        this.vehicleCapacity = vehicleCapacity;
        this.maxRideTime = maxRideTime;
        this.pickupRideStops = pickupRideStops;
        this.dropRideStops = dropRideStops;
        this.depot = depot;

        this.number_of_start_depots = nVehicles; //A depot for each vehicle
        this.number_of_end_depots= nVehicles; // A end depot for each vehicles
        this.number_of_tasks = pickupRideStops.size();
        this.n = (number_of_tasks*2) + nVehicles + nVehicles;
        this.first_end_depot = nVehicles+(number_of_tasks*2);

        this.distanceMatrix = new int[n][n];
        this.pickupAndDropRideStops = new ArrayList<>();
        buildPickUpAndDrop(pickupAndDropRideStops,pickupRideStops,dropRideStops,depot,number_of_end_depots,maxRouteDuration);
        computeDistanceMatrix(distanceMatrix,nVehicles,number_of_tasks,pickupAndDropRideStops,depot);

    }

    public void buildModel(){
        //Intizialization of variables
        cp = makeSolver();
        //Create Variables
        succ = makeIntVarArray(cp, n, n); //succ[i] is the successor node of i
        pred = makeIntVarArray(cp,n,n); //pred[i] is the predecessor node of i
        distSucc = makeIntVarArray(cp, n, 0, maxRideTime+1); //distSucc[i] is the distance between node i and its successor
        peopleOn = makeIntVarArray(cp, n, vehicleCapacity+1); //peopleOn[i] is the number of people on the vehicle when it visit the node i
        index = makeIntVarArray(cp, n, n); //index[i] is the position of the node i in the path (so index[i]=i)
        visitedByVehicle = makeIntVarArray(cp, n, nVehicles); //VisitedByVehicle[i] is the vehicle that visit the node i
        managedBy= makeIntVarArray(cp,number_of_tasks,nVehicles); //IntVar that represent the vehicle that manage the pickup node i

        time = new IntVar[n]; //time[i] is the time when the vehicle visit the n
        //intilize domain time

        //intilize domain time
        for (int i = 0; i < nVehicles; i++) {
            time[i]=makeIntVar(cp,1); //the time at the depot is 0
            time[first_end_depot+i]=makeIntVar(cp,maxRouteDuration+1);
        }
        for (int i = nVehicles; i < nVehicles+number_of_tasks; i++) {
            int task=i-nVehicles;
            int dropNode= i +number_of_tasks;
            time[i]=makeIntVar(cp,0,pickupRideStops.get(task).window_end+1);
            time[dropNode]=makeIntVar(cp,0,dropRideStops.get(task).window_end+1);
        }

        //Adding circuit constraint
        cp.post(new Circuit(succ));
        cp.post(new Circuit(pred));

        //Channelling between pred and succ
        for (int i = 0; i < n; i++) {
            cp.post(new Element1DVar(pred, succ[i],index[i]));
            cp.post(new Element1DVar(succ,pred[i],index[i]));
        }

        //vehicleCapacity minium and maximum value
        for (int i = 0; i < n; i++) {
            cp.post(lessOrEqual(peopleOn[i],vehicleCapacity));
            cp.post(largerOrEqual(peopleOn[i],0)); //the veichle capacity must be at least 0
        }


        //Manage Time PeopleOn VisitByVehicle at Start and at the End
        //Departure time from the depots
        for (int i = 0; i < nVehicles; i++) {
            int start_depot=i;
            int end_depot=nVehicles+number_of_tasks+number_of_tasks+i;
            //Manage the start depot
            cp.post(equal(time[i],0)); //the time at start depot is 0
            cp.post(equal(peopleOn[i],0)); //people on start depot is 0
            cp.post(equal(visitedByVehicle[i],i)); //start depot 0 is visited by i
            cp.post(new Element1DVar(visitedByVehicle, succ[i],visitedByVehicle[i]));
            //The successor of the start depot can only be a pickup node
            //The successor of the start depot could't be a end depot (Waste a vehicle)
            for (int node = nVehicles+number_of_tasks; node < n ; node++) {
                cp.post(notEqual(succ[i],node));
            }
            //update distance from the successor
            cp.post(new Element1D(distanceMatrix[i], succ[i],distSucc[i]));
            //update time
            cp.post(new Element1DVar(time, succ[i],sum(time[i],distSucc[i])));

            //manage the end depot
            cp.post(lessOrEqual(time[end_depot],maxRouteDuration));
            cp.post(equal(peopleOn[end_depot],0));
            //The successor of the end depot can only be a start depot
            cp.post(equal(succ[end_depot],(i+1)%nVehicles));
            cp.post(equal(pred[i],(end_depot)%nVehicles));
            //cp.post(equal(visitedByVehicle[end_depot],visitedByVehicle[(i+1)%nVehicles]));
            cp.post(new Element1DVar(visitedByVehicle,index[i],visitedByVehicle[end_depot]));

            //The pred of the end depot must have the same vehicle
            cp.post(new Element1DVar(visitedByVehicle,pred[end_depot],visitedByVehicle[end_depot]));
            //the pred of the end depot couldnt be a pickup
            for (int pickup = nVehicles; pickup < nVehicles+number_of_tasks; pickup++) {
                cp.post(notEqual(pred[end_depot],pickup));
            }
            cp.post(lessOrEqual(time[first_end_depot+i],maxRouteDuration));
            cp.post(equal(peopleOn[first_end_depot+i],0));


            //the pred of the end depot must have 0 people on
            cp.post(new Element1DVar(peopleOn,pred[end_depot],peopleOn[end_depot]));
            //the pred of the end depot must arrive in time
            IntVar timePred = elementVar(time,pred[end_depot]);
            IntVar distPred = element(distanceMatrix[end_depot],pred[end_depot]);
            cp.post(lessOrEqual(sum(timePred,distPred),maxRouteDuration));


        }


        //TODO 2.5 Time PeopleOn VisitByVehicle at the Pickup and Drop nodes
        for (int i = nVehicles; i <nVehicles+number_of_tasks; i++) {
            int task_id = i - nVehicles;
            int pickup = i;
            int drop = i + number_of_tasks;


            //Update distance and time from successor
            cp.post(new Element1D(distanceMatrix[pickup], succ[pickup], distSucc[pickup]));
            cp.post(new Element1DVar(time, succ[pickup], sum(time[pickup], distSucc[pickup])));
            cp.post(lessOrEqual(time[pickup], maxRouteDuration));
            cp.post(lessOrEqual(time[pickup], pickupRideStops.get(task_id).window_end));
            cp.post(lessOrEqual(time[pickup], dropRideStops.get(task_id).window_end - distanceMatrix[pickup][drop])); //mandatory time
            cp.post(lessOrEqual(time[pickup], time[drop])); //first visit the pick up and then the drop
            //the pred of a pick must have the time to reach the pick up
            IntVar timePred = elementVar(time,pred[pickup]);
            IntVar distPred = element(distanceMatrix[pickup],pred[pickup]);
            cp.post(lessOrEqual(sum(timePred,distPred),pickupRideStops.get(task_id).window_end));


            cp.post(new Element1D(distanceMatrix[drop], succ[drop], distSucc[drop]));
            cp.post(new Element1DVar(time, succ[drop], sum(time[drop], distSucc[drop])));
            cp.post(lessOrEqual(time[drop], maxRouteDuration));
            cp.post(lessOrEqual(time[drop], dropRideStops.get(task_id).window_end));
            cp.post(lessOrEqual(time[drop], plus(time[pickup], maxRideTime))); //max time
            cp.post(largerOrEqual(time[drop], time[pickup])); //first vist the pick up and the drop
            //the pred of a drop must have the time to reach the drop
            IntVar timePred_d = elementVar(time,pred[drop]);
            IntVar distPred_d = element(distanceMatrix[drop],pred[drop]);
            cp.post(lessOrEqual(sum(timePred_d,distPred_d),dropRideStops.get(task_id).window_end));

            //Manage people
            //peopleOn[i]=peopleOn[pred[i]]+1
            IntVar peopleOnPred = elementVar(peopleOn, pred[pickup]);
            cp.post(equal(peopleOn[pickup], plus(peopleOnPred, 1)));
            cp.post(lessOrEqual(peopleOnPred, vehicleCapacity-1)); //beacuse we need to take a person

            IntVar peopleOnPred_d = elementVar(peopleOn, pred[drop]);
            cp.post(equal(peopleOn[drop], minus(peopleOnPred_d, 1)));
            cp.post(largerOrEqual(peopleOnPred_d, 1)); //beacuse we need to drop a person

            //Capacity
            cp.post(lessOrEqual(peopleOn[pickup], vehicleCapacity));
            cp.post(lessOrEqual(peopleOn[drop], vehicleCapacity));
            cp.post(largerOrEqual(peopleOn[pickup], 1)); //because we took a person
            cp.post(largerOrEqual(peopleOn[drop], 0)); //because we drop a person

            //Manage visitedByVehicle
            cp.post(new Element1DVar(visitedByVehicle, succ[pickup], visitedByVehicle[pickup]));
            cp.post(new Element1DVar(visitedByVehicle, succ[drop], visitedByVehicle[drop]));
            cp.post(new Element1DVar(visitedByVehicle, pred[pickup], visitedByVehicle[pickup]));
            cp.post(new Element1DVar(visitedByVehicle, pred[drop], visitedByVehicle[drop]));

            cp.post(new Element1DVar(visitedByVehicle, index[pickup], visitedByVehicle[drop]));
            cp.post(new Element1DVar(visitedByVehicle, index[drop], visitedByVehicle[pickup]));

            //Manage managedBy
            cp.post(new Element1DVar(managedBy, minus(index[pickup], nVehicles), visitedByVehicle[pickup]));
            //The vehicle that manage the pickup must be the same that manage the drop
            cp.post(new Element1DVar(visitedByVehicle,index[drop], managedBy[task_id]));


            //Constrain to shrik the space
            cp.post(notEqual(pred[pickup], drop));
            cp.post(notEqual(succ[drop], pickup));

            for (int endDepot = first_end_depot; endDepot < n; endDepot++) {
                int startDepot = i - nVehicles - (number_of_tasks * 2);
                cp.post(notEqual(succ[pickup], endDepot));
                cp.post(notEqual(pred[pickup], endDepot));
                cp.post(notEqual(pred[drop], endDepot));
                cp.post(notEqual(pred[drop], startDepot));
            }


        }


        totalDist = sum(distSucc);
        obj_function=cp.minimize(totalDist);




    }


    public DialARideSolution getFirstSol(){

        //Compute the first solution
        final int totalSolution = 1;
        DialARideSolution[] solutions = new DialARideSolution[totalSolution];
        for (int i = 0; i <totalSolution; i++) {
            solutions[i]=new DialARideSolution(nVehicles, pickupRideStops, dropRideStops, depot, vehicleCapacity, maxRideTime, maxRouteDuration);
        }

        DFSearch dfs= makeDfs(cp, () -> {

            //Select First Unfixed Variable
            int selected = -1;
            IntVar xs=null;
            for (int i = 0; i < n; i++) {
                if(!succ[i].isFixed() && pred[i].isFixed() && (selected==-1 || succ[i].size()<xs.size())){
                    selected=i;
                    xs=succ[i];
                }
            }
            if(xs==null) return EMPTY;

            // Get the index of the selected node
            System.out.println(selected);



            // Create a list of all possible successors with their distances
            int[] nearestNodes = new int[xs.size()];
            xs.fillArray(nearestNodes);
            //if nearestNodes doesnt contains dropnode and i'm at drop node without people on the veichle then incocystency
            //beacuse in this path i can't reach the end depot
            if(isADrop(selected) && peopleOn[selected].max()==0){
                boolean notFind=true;
                for (int node : nearestNodes) {
                    if(isADepot(node)){
                        notFind=false;
                    }
                }
                if(notFind) throw new InconsistencyException();
            }

            System.out.println("Nearest Nodes: "+Arrays.toString(nearestNodes));



            int curr_position= selected;
            List<Integer> nearestNodes_filtered = Arrays.stream(nearestNodes)
                    //.filter(successor -> isWorth(curr_position,successor))
                    .boxed().collect(Collectors.toList());

            System.out.println("Nearest Nodes Filtered: "+nearestNodes_filtered.toString());


            double mostUrgent = Integer.MAX_VALUE;
            int mostUrgentNode=-1;



            // Trova il nodo con il costo totale più basso come il nodo successivo
            for (int node : nearestNodes_filtered) {
                // Calcola il costo basato sulla finestra temporale
                int windowDiff = pickupAndDropRideStops.get(node-nVehicles).window_end-time[selected].min();
                // Calcola il costo basato sulla distanza
                int distanceCost = distanceMatrix[selected][node];


                // Calcola il costo totale come la somma dei costi basati sulla finestra temporale e sulla distanza
                double cost_time=windowDiff*distanceCost;

                if (cost_time < mostUrgent) {
                    mostUrgent = cost_time;
                    mostUrgentNode = node;
                }




            }


            System.out.println("Nearest Nodes sorted by cost: "+mostUrgentNode);
            System.out.println("VisitedByVehicle: "+visitedByVehicle[selected]);
            System.out.println("Time: "+time[selected].min());
            System.out.println("Pred:"+pred[selected]);
            System.out.println("Node: "+selected);
            System.out.println("MaxRouting Time: "+maxRouteDuration);
            System.out.println("MostUrgent Node: "+vehicleCapacity);


            if(mostUrgentNode==-1 ) {
                throw new InconsistencyException();
            }

            //ok now go to the nearest and then go to the most urgent but check if i can do that
            int finalSelected = selected;
            int best= mostUrgentNode;


            System.out.println("Sono andato al nearest");
            return branch(() -> {cp.post(equal(succ[finalSelected],best));},
                    () -> {cp.post(notEqual(succ[finalSelected],best));});
        });

        //TODO 2.7 ACTION ON SOLUTION
        AtomicInteger acc= new AtomicInteger();
        final int[] bestPath = new int[n];
        final int[] bestRideID = new int[n];

        AtomicInteger curr_solution= new AtomicInteger();
        dfs.onSolution(() -> {

            acc.getAndIncrement();
            System.out.println("solution: "+totalDist.min());
            System.out.println("Max Routing Time: "+maxRouteDuration);
            for (int i = 0; i < n; i ++) bestPath[i] = succ[i].min();
            for (int i = 0; i < n; i ++) bestRideID[i] = visitedByVehicle[i].min();
            System.out.println("Best path: "+Arrays.toString(bestPath));
            System.out.println("Best ride ID: "+Arrays.toString(bestRideID));


            for (int i = 0; i < nVehicles; i++) {
                System.out.println("Vehicle "+i);
                int current = i;
                StringBuilder path = new StringBuilder();
                StringBuilder vehicleRideID = new StringBuilder();
                StringBuilder timeString = new StringBuilder();
                StringBuilder sizeString = new StringBuilder();
                while (current < nVehicles + pickupRideStops.size() + dropRideStops.size()) {
                    path.append(current+",");
                    vehicleRideID.append(bestRideID[current]+",");
                    timeString.append(time[current].min()+",");
                    sizeString.append(peopleOn[current].min()+",");
                    current = bestPath[current];
                }
                path.append(bestPath[current]);
                vehicleRideID.append(bestRideID[current]);
                timeString.append(time[current].min());
                sizeString.append(peopleOn[current].min());
                System.out.println("Time: "+timeString.toString());
                System.out.println("Path: "+path.toString());
                System.out.println("Ride ID: "+vehicleRideID.toString());
                System.out.println("Size: "+sizeString.toString());

            }


            final int idx_sol= curr_solution.get();
            System.out.println("I pickup vanno da "+nVehicles+" a "+(nVehicles+pickupRideStops.size()));
            System.out.println("I drop vanno da "+(nVehicles+pickupRideStops.size())+" a "+(nVehicles+pickupRideStops.size()+dropRideStops.size()));
            for (int i = 0; i < nVehicles; i++) {
                int curr_node = i;
                while (curr_node < nVehicles + pickupRideStops.size() + dropRideStops.size()) {
                    int succ_node=bestPath[curr_node];
                    boolean isPickup = succ_node>=nVehicles && succ_node<nVehicles+pickupRideStops.size();
                    if(succ_node>=nVehicles+pickupRideStops.size()+dropRideStops.size()){
                        //The veichle is back to the depot
                        curr_node = succ_node;
                        break;
                    }
                    if(isPickup) {
                        solutions[idx_sol].addStop(bestRideID[curr_node],succ_node-nVehicles,isPickup);
                    }
                    else {
                        solutions[idx_sol].addStop(bestRideID[curr_node],succ_node-pickupRideStops.size()-nVehicles,isPickup);
                    }
                    curr_node = succ_node;
                }
            }
            curr_solution.getAndIncrement();

        });


        SearchStatistics stats=dfs.solve(statistics -> statistics.numberOfSolutions() ==totalSolution);

        System.out.println("Ho ritornato la soluzione");
        System.out.println("Number of failure: "+stats.numberOfFailures());
        System.out.println("Number of nodes: "+stats.numberOfNodes());

        return solutions[0];

    }

    private boolean isWorth(int curr_position,int successor){

        int number_of_task=pickupRideStops.size();
        System.out.println("i'm at curr_position: "+curr_position);

        if (isADepot(successor)){
            return evaluateDepotNode(curr_position,successor);
        }

        else if(isADrop(successor)){
            return evaluateDropNode(curr_position,successor);
        }

        //it's a pickup node
        else if (isAPickup(successor)){
            return evaluatePickUpNode(curr_position,successor);
        }
        return true;

    }


    private boolean isADepot(int node){
        return node<nVehicles || node>=first_end_depot;
    }

    private boolean isADrop(int node){
        return node>=nVehicles+number_of_tasks && node<first_end_depot;
    }

    private boolean isAPickup(int node){
        return node>=nVehicles && node<nVehicles+number_of_tasks;
    }

    private boolean evaluateDepotNode(int curr_position, int successor){





        //ok i have 0 person on the veichle and i'm at drop
        //i can go to a pickup if exist or to the end depot
        //if i go to a pickup i need to check if i can reach the drop node and then also the final depot.
        int currTime_tmp = time[curr_position].min();
        int n= visitedByVehicle.length; //total nodes
        int upperBoundPickup= n-nVehicles-pickupRideStops.size();
        for (int i = nVehicles; i < upperBoundPickup; i++) {
            if(!pred[i].isFixed()){ //not anyone visited the node i yet
                int task = i-nVehicles;
                int window_end_pickup = pickupRideStops.get(task).window_end;
                int window_end_drop = dropRideStops.get(task).window_end;
                int pickupNode = i;
                int depotNode = i+pickupRideStops.size();
                currTime_tmp+=distanceMatrix[curr_position][pickupNode];
                if(currTime_tmp>=window_end_pickup){
                    //ok i cant do this task, try the next
                    continue;
                }
                currTime_tmp+=distanceMatrix[pickupNode][depotNode];
                if(currTime_tmp>=window_end_drop){
                    //ok i cant do this task, try the next

                    continue;
                }
                currTime_tmp+=distanceMatrix[depotNode][successor];
                if(currTime_tmp>=maxRouteDuration){
                    //ok i cant do this task, try the next

                    continue;
                }
                //ok it's not worth to go to the depot beacuse exist a path that i can do
                return false;
            }
        }

        //i cant do any task, go to the depot


        return true;
    }

    private boolean evaluateDropNode(int curr_position,int successor){
        //The nearest node is a drop so evaluate if it's worth going to this node
        //if the veichle is almost empty then it's not worth going to this node
        //eh ma aspetta e se mi sta per scadere?
        /*
        if(peopleOn[curr_position].max()==1 && !isAPickup(curr_position,nVehicles,pickupRideStops.size())){
            return false;
        }
         */
        //if the drop node it's not managed by the veichle that visit the pickup node then it's not worth going to this node

        int pickupNode=successor-pickupRideStops.size();
        //questa cosa non fuziona
        /*
        if(!managedBy[pickupNode-nVehicles].isFixed()){
            System.out.println("ho tolto il drop");
            //!pred[pickupNode].isFixed() if true then the pickup node is not yet visited so it's not my task
            //VisitedByVehicle[pickupNode].min()!=VisitedByVehicle[curr_position].min() if true then the pickup node is not managed by my veichle
            return false;
        }
        */


        //if the veichle can't reach the node before the window end
        /*
        if(time[curr_position].min()+distanceMatrix[curr_position][successor]>dropRideStops.get(successor-nVehicles-pickupRideStops.size()).window_end){
            return false;
        }

         */




        return true;
    }

    private boolean evaluatePickUpNode(int curr_position, int successor){
        //1. If the veichle is full then it's not worth going to the pickup node
        //2. If the veichle can't reach the pickup node before the window end then it's not worth going to the pickup node
        //3. If the veichle can't reach the drop node of each task that the veichle has to do plus this task then it's not worth going to the pickup node

        //The nearest node is a pickup so evaluate if it's worth going to this node
        //if our veichle capacity is full then it's not worth going to this node
        //peopleOn[curr_node+1] because the person is not yet on the veichle

        int task_id=successor-nVehicles;


        System.out.println("Maybe i can do it this : "+successor);

        //Take all the task that the vehicle has to do
        int vehicle_id = visitedByVehicle[curr_position].min();
        List<Integer> taskManagedByCurrentVehicle = new ArrayList<>();
        for (int i = 0; i < managedBy.length; i++) {
            int pickupNode = i+nVehicles;
            if(managedBy[i].isFixed() && visitedByVehicle[pickupNode].min()==vehicle_id){
                //ok it's a my task, but i already did it?
                int dropNode = pickupNode+pickupRideStops.size();
                if(!pred[dropNode].isFixed()){
                    //the task is not already done
                    taskManagedByCurrentVehicle.add(i);
                }
            }
        }
        System.out.println("Task managed by the veichle: "+taskManagedByCurrentVehicle.toString());

        //Ok add the task associated to the succesor node
        if(taskManagedByCurrentVehicle.isEmpty()){//ok i don't have any task to do
            //but i can reach the drop node of the new task?
            int start_time = time[curr_position].min()+distanceMatrix[curr_position][successor];
            int dropNode = successor+pickupRideStops.size();
            if(start_time>pickupRideStops.get(task_id).window_end){
                return false;
            }
            start_time+=distanceMatrix[successor][dropNode];
            if(start_time>dropRideStops.get(task_id).window_end){
                return false;
            }
            //ok i can reach the drop node in time, but i can reach the depot?
            start_time+=distanceMatrix[dropNode][distanceMatrix.length-1];
            if(start_time>maxRouteDuration){
                return false;
            }
            //ok i can go to the nearest node without a lot of problem
            return true;
        }
        //Ok now if from current_time the total_time to go to each node is under the maxRouteDuration
        //then maybe it's worth going to the nearest node


        //ok now from the new successor node i need to check if the veichle can reach the drop node of each task
        //that the veichle has to do in window_end order

        taskManagedByCurrentVehicle.sort
                (Comparator.comparingInt(task ->
                        dropRideStops.get(task).window_end));

        int mostUrgentTask = taskManagedByCurrentVehicle.get(0);
        int mostUrgentPickup = mostUrgentTask+nVehicles;
        int mostUrgentDrop = mostUrgentPickup+pickupRideStops.size();
        int start_time_most_urgent = time[mostUrgentPickup].min();

        int currTime_tmp = time[curr_position].min()+distanceMatrix[curr_position][successor];
        int currNode_tmp = successor;
        currTime_tmp+=distanceMatrix[currNode_tmp][mostUrgentDrop];
        if(currTime_tmp>dropRideStops.get(mostUrgentTask).window_end){
            return false;
        }
        if(currTime_tmp>maxRouteDuration){
            return false;
        }
        if(currTime_tmp-start_time_most_urgent>maxRideTime){
            return false;
        }
        //Ok we can go to the nearest node without a lot of problem maybe
        return true;


    }

    private static IntVar elementVar(IntVar[] array, IntVar y){
        Solver cp = y.getSolver();
        int min = Arrays.stream(array).mapToInt(IntVar::min).min().getAsInt();
        int max = Arrays.stream(array).mapToInt(IntVar::max).max().getAsInt();
        IntVar z = makeIntVar(cp, min,max);
        cp.post(new Element1DVar(array, y, z));
        return z;
    }



    private static void computeDistanceMatrix(int[][] distanceMatrix, int nVehicles, int number_of_tasks,
                                              ArrayList<RideStop> pickupAndDropRideStops, RideStop depot){
        int first_end_depot=nVehicles+(number_of_tasks*2);
        for (int i = 0 ; i < distanceMatrix.length ; ++i) {
            for (int j = 0 ; j < distanceMatrix.length; ++j) {

                if(i<nVehicles && j<nVehicles){
                    //i and j are depots
                    distanceMatrix[i][j] = 0;
                }
                else if(i<nVehicles && !(j>=first_end_depot)){
                    //only i is a depot
                    distanceMatrix[i][j] = distance(depot, pickupAndDropRideStops.get(j-nVehicles));
                }
                else if(j<nVehicles && !(i>first_end_depot)){
                    //only j is a depot
                    distanceMatrix[i][j] = distance(pickupAndDropRideStops.get(i-nVehicles), depot);
                }
                else if (i < first_end_depot && j < first_end_depot){
                    //i and j are not depots
                    distanceMatrix[i][j] = distance(pickupAndDropRideStops.get(i-nVehicles), pickupAndDropRideStops.get(j-nVehicles));
                }
                else if(i>=first_end_depot && j>=first_end_depot){
                    //i and j are end depots
                    distanceMatrix[i][j]=0;
                }
                else if(i>=first_end_depot && j<nVehicles){
                    //i is a end depot and j is a start depot
                    distanceMatrix[i][j] =0;
                }
                else if(j>=first_end_depot && i<nVehicles){
                    //j is a end depot and i is a start depot
                    distanceMatrix[i][j] = 0;
                }
                else if(i>=first_end_depot){
                    //i is a end depot and j is a place
                    distanceMatrix[i][j] = distance(depot, pickupAndDropRideStops.get(j-nVehicles));
                }
                else if(j>=first_end_depot){
                    //j is a end depot and i is a place
                    distanceMatrix[i][j] = distance(pickupAndDropRideStops.get(i-nVehicles), depot);
                }
            }
        }

    }


    private static void buildPickUpAndDrop(ArrayList<RideStop> pickupAndDropRideStops, ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops, RideStop depot, int number_of_end_depots, int maxRouteDuration){
        pickupAndDropRideStops.addAll(pickupRideStops);
        pickupAndDropRideStops.addAll(dropRideStops);
        for (int i = 0; i < number_of_end_depots; i++) {
            RideStop endDepot = new RideStop();
            endDepot.type=0;
            endDepot.pos_x=depot.pos_x;
            endDepot.pos_y=depot.pos_y;
            endDepot.window_end=maxRouteDuration;
            pickupAndDropRideStops.add(endDepot);
        }
    }

    public static DialARideSolution solve(int nVehicles, int maxRouteDuration, int vehicleCapacity,
                                          int maxRideTime, ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,
                                          RideStop depot) {

        //Create the model
        DialARide dialARide = new DialARide(nVehicles, maxRouteDuration, vehicleCapacity, maxRideTime, pickupRideStops, dropRideStops, depot);
        dialARide.buildModel();
        DialARideSolution sol=dialARide.getFirstSol();

        return sol;


    }



    private static boolean heuristic(int curr_position, int successor,int nVehicles,int vehicleCapacity,
                                     ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,IntVar[] managedBy,
                                     IntVar[] peopleOn, IntVar[] time,IntVar[] visitedByVehicle,IntVar[] pred,IntVar[] succ,
                                     int[][] distanceMatrix, int maxRouteDuration,int maxRideTime){
        int number_of_task=pickupRideStops.size();

        System.out.println("Sono a curr Position: "+curr_position);


        if (isADepot(successor,nVehicles,number_of_task)){
            System.out.println("Stiamo valutando un Depot");
            return evaluateDepotNode(curr_position,successor, nVehicles, vehicleCapacity, pickupRideStops, dropRideStops,
                    managedBy, peopleOn, time, visitedByVehicle, pred, succ, distanceMatrix, maxRouteDuration, maxRideTime);
        }


        else if(isADrop(successor,nVehicles,number_of_task)){
            System.out.println("Stiamo valutando un Drop");
            return evaluateDropNode(curr_position,successor, nVehicles, vehicleCapacity, pickupRideStops, dropRideStops,
                    managedBy, peopleOn, time, visitedByVehicle, pred, succ, distanceMatrix, maxRouteDuration, maxRideTime);
        }

        //it's a pickup node
        else if (isAPickup(successor,nVehicles,number_of_task)){
            System.out.println("Stiamo valutando un Pickup");
            return evaluatePickupNode(curr_position,successor, nVehicles, vehicleCapacity, pickupRideStops, dropRideStops,
                    managedBy, peopleOn, time, visitedByVehicle, pred, succ, distanceMatrix, maxRouteDuration, maxRideTime);
        }
        return true;

    }

    private static boolean evaluateDepotNode(int curr_position, int successor,int nVehicles,int vehicleCapacity,
                                             ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,IntVar[] managedBy,
                                             IntVar[] peopleOn, IntVar[] time,IntVar[] visitedByVehicle,IntVar[] pred,IntVar[] succ,
                                             int[][] distanceMatrix, int maxRouteDuration,int maxRideTime){
        //1. Dont go to the DepotNode if the veichle  (Constraint does for us)
        //2. Dont go to the DepotNode if there are tasks to do and i can manage one of them
        if(isAPickup(curr_position,nVehicles,pickupRideStops.size())) {
            System.out.println("Non è stato aggiunto perchè siamo in un pickup");
            return false;
        }

        if(isADrop(curr_position,nVehicles,pickupRideStops.size()) && peopleOn[curr_position].max()!=0){
            System.out.println("Sto valutando di andare a un deposito Non è stato aggiunto perchè ci sono ancora persone da portare in ospedale");
            return false;
        }

        //ok i have 0 person on the veichle and i'm at the depot
        //i can go to a pickup if exist or to the end depot
        //if i go to a pickup i need to check if i can reach the drop node and then also the final depot.
        int currTime_tmp = time[curr_position].min();
        int n= visitedByVehicle.length; //total nodes
        int upperBoundPickup= n-nVehicles-pickupRideStops.size();
        for (int i = nVehicles; i < upperBoundPickup; i++) {
            if(!pred[i].isFixed()){
                int task = i-nVehicles;
                int window_end_pickup = pickupRideStops.get(task).window_end;
                int window_end_drop = dropRideStops.get(task).window_end;
                int pickupNode = i;
                int depotNode = i+pickupRideStops.size();
                currTime_tmp+=distanceMatrix[curr_position][pickupNode];
                if(currTime_tmp>=window_end_pickup){
                    continue;
                }
                currTime_tmp+=distanceMatrix[pickupNode][depotNode];
                if(currTime_tmp>=window_end_drop){
                    continue;
                }
                currTime_tmp+=distanceMatrix[depotNode][successor];
                if(currTime_tmp>=maxRouteDuration){
                    continue;
                }
                System.out.println("A task is available and i can do it");
                //ok it's not worth to go to the depot beacuse exist a path that i can do
                return false;
            }
        }

        return true;
    }

    private static boolean evaluatePickupNode(int curr_position, int successor,int nVehicles,int vehicleCapacity,
                                              ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,IntVar[] managedBy,
                                              IntVar[] peopleOn, IntVar[] time,IntVar[] visitedByVehicle,IntVar[] pred,IntVar[] succ,
                                              int[][] distanceMatrix, int maxRouteDuration,int maxRideTime){
        //1. If the veichle is full then it's not worth going to the pickup node
        //2. If the veichle can't reach the pickup node before the window end then it's not worth going to the pickup node
        //3. If the veichle can't reach the drop node of each task that the veichle has to do plus this task then it's not worth going to the pickup node

        //The nearest node is a pickup so evaluate if it's worth going to this node
        //if our veichle capacity is full then it's not worth going to this node
        //peopleOn[curr_node+1] because the person is not yet on the veichle

        int task_id=successor-nVehicles;
        if(!isADepot(curr_position,nVehicles,pickupRideStops.size()) && (peopleOn[curr_position].max())==vehicleCapacity){
            //the veichle is full, also the constraint check that.
            System.out.println("Non posso andare a questo pickup perchè il veicolo è pieno"+successor);
            return false;
        }

        System.out.println("Maybe i can do it this : "+successor);

        //Take all the task that the vehicle has to do
        int vehicle_id = visitedByVehicle[curr_position].min();
        List<Integer> taskManagedByCurrentVehicle = new ArrayList<>();
        for (int i = 0; i < managedBy.length; i++) {
            int pickupNode = i+nVehicles;
            if(managedBy[i].isFixed() && visitedByVehicle[pickupNode].min()==vehicle_id){
                //ok it's a my task, but i already did it?
                int dropNode = pickupNode+pickupRideStops.size();
                if(!pred[dropNode].isFixed()){
                    //the task is not already done
                    taskManagedByCurrentVehicle.add(i);
                }
            }
        }
        System.out.println("Task managed by the veichle: "+taskManagedByCurrentVehicle.toString());

        //Ok add the task associated to the succesor node
        if(taskManagedByCurrentVehicle.isEmpty()){//ok i don't have any task to do
            //but i can reach the drop node of the new task?
            int start_time = time[curr_position].min()+distanceMatrix[curr_position][successor];
            int dropNode = successor+pickupRideStops.size();
            if(start_time>pickupRideStops.get(task_id).window_end){
                return false;
            }
            start_time+=distanceMatrix[successor][dropNode];
            if(start_time>dropRideStops.get(task_id).window_end){
                return false;
            }
            //ok i can reach the drop node in time, but i can reach the depot?
            start_time+=distanceMatrix[dropNode][distanceMatrix.length-1];
            if(start_time>maxRouteDuration){
                return false;
            }
            //ok i can go to the nearest node without a lot of problem
            return true;
        }
        //Ok now if from current_time the total_time to go to each node is under the maxRouteDuration
        //then maybe it's worth going to the nearest node


        //ok now from the new successor node i need to check if the veichle can reach the drop node of each task
        //that the veichle has to do in window_end order

        taskManagedByCurrentVehicle.sort
                (Comparator.comparingInt(task ->
                        dropRideStops.get(task).window_end));

        int mostUrgentTask = taskManagedByCurrentVehicle.get(0);
        int mostUrgentPickup = mostUrgentTask+nVehicles;
        int mostUrgentDrop = mostUrgentPickup+pickupRideStops.size();
        int start_time_most_urgent = time[mostUrgentPickup].min();

        int currTime_tmp = time[curr_position].min()+distanceMatrix[curr_position][successor];
        int currNode_tmp = successor;
        currTime_tmp+=distanceMatrix[currNode_tmp][mostUrgentDrop];
        if(currTime_tmp>dropRideStops.get(mostUrgentTask).window_end){
            return false;
        }
        if(currTime_tmp>maxRouteDuration){
            return false;
        }
        if(currTime_tmp-start_time_most_urgent>maxRideTime){
            return false;
        }
        //Ok we can go to the nearest node without a lot of problem maybe
        return true;

    }





    private static boolean evaluateDropNode(int curr_position, int successor,int nVehicles,int vehicleCapacity,
                                            ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,IntVar[] managedBy,
                                            IntVar[] peopleOn, IntVar[] time,IntVar[] visitedByVehicle,IntVar[] pred,IntVar[] succ,
                                            int[][] distanceMatrix, int maxRouteDuration,int maxRideTime){

        //The nearest node is a drop so evaluate if it's worth going to this node
        //if the veichle is almost empty then it's not worth going to this node
        //eh ma aspetta e se mi sta per scadere?
        /*
        if(peopleOn[curr_position].max()==1 && !isAPickup(curr_position,nVehicles,pickupRideStops.size())){
            return false;
        }
         */
        //if the drop node it's not managed by the veichle that visit the pickup node then it's not worth going to this node
        int pickupNode=successor-pickupRideStops.size();
        if(!visitedByVehicle[pickupNode].isFixed() || visitedByVehicle[pickupNode].min()!=visitedByVehicle[curr_position].min()){
            //!VisitedByVehicle[pickupNode].isFixed() if true then the pickup node is not yet visited so it's not my task
            //VisitedByVehicle[pickupNode].min()!=VisitedByVehicle[curr_position].min() if true then the pickup node is not managed by my veichle
            return false;
        }
        /*
        //if the veichle can't reach the node before the window end
        if(time[curr_position].min()+distanceMatrix[curr_position][successor]>dropRideStops.get(successor-nVehicles-pickupRideStops.size()).window_end){
            return false;
        }

         */


        return true;
    }

    private static boolean isADepot(int index_node, int nVehicles, int task){
        return index_node>=nVehicles+(task*2);
    }

    private static boolean isAPickup(int index_node,int nVehicles,int task){
        return index_node>=nVehicles && index_node<nVehicles+task;
    }

    private static boolean isADrop(int index_node,int nVehicles,int task){
        return index_node>=nVehicles+task && index_node<nVehicles+(task*2);
    }



    private static void computeDistanceMatrix(int[][] distanceMatrix,ArrayList<RideStop> pickupNodes,ArrayList<RideStop> dropNode, RideStop depot, int nVehicles, int duplicate_depot,int end_depot){
        ArrayList<RideStop> nodes= new ArrayList<RideStop>();
        nodes.addAll(pickupNodes);
        nodes.addAll(dropNode);
        for (int i = 0 ; i < distanceMatrix.length ; ++i) {
            for (int j = 0 ; j < distanceMatrix.length; ++j) {
                if(i<nVehicles && j<nVehicles){
                    //i and j are depots
                    distanceMatrix[i][j] = 0;
                }
                else if(i<nVehicles && !(j>=nVehicles+nodes.size())){
                    //only i is a depot
                    distanceMatrix[i][j] = distance(depot, nodes.get(j-duplicate_depot));
                }
                else if(j<nVehicles && !(i>=nVehicles+nodes.size())){
                    //only j is a depot
                    distanceMatrix[i][j] = distance(nodes.get(i-duplicate_depot), depot);
                }
                else if (i < nVehicles + nodes.size() && j < nVehicles + nodes.size()){
                    //i and j are not depots
                    distanceMatrix[i][j] = distance(nodes.get(i-duplicate_depot), nodes.get(j-duplicate_depot));
                }
                else if(i>=nVehicles+nodes.size() && j>=nVehicles+nodes.size()){
                    //i and j are end depots
                    distanceMatrix[i][j]=0;
                }
                else if(i>=nVehicles+nodes.size() && j<nVehicles){
                    //i is a end depot and j is a start depot
                    distanceMatrix[i][j] =0;
                }
                else if(j>=nVehicles+nodes.size() && i<nVehicles){
                    //j is a end depot and i is a start depot
                    distanceMatrix[i][j] = 0;
                }
                else if(i>=nVehicles+nodes.size()){
                    //i is a end depot and j is a place
                    distanceMatrix[i][j] = distance(depot, nodes.get(j-duplicate_depot));
                }
                else if(j>=nVehicles+nodes.size()){
                    //j is a end depot and i is a place
                    distanceMatrix[i][j] = distance(nodes.get(i-duplicate_depot), depot);
                }
            }
        }
    }

    public static List<DialARideSolution> findAll(int nVehicles, int maxRouteDuration, int vehicleCapacity,
                                                  int maxRideTime, ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,
                                                  RideStop depot) {
        // TODO
        // Given a series of dial-a-ride request made by single persons (for request i, pickupRideStops[i] gives the spot
        // where the person wants to be taken, and dropRideStops[i] the spot where (s)he would like to be dropped),
        // minimize the total ride time of all the vehicles.
        // You have nVehicles vehicles, each of them can take at most vehicleCapacity person inside at any time.
        // The maximum time a single person can remain in the vehicle is maxRideTime, and the maximum time a single
        // vehicle can be on the road for a single day is maxRouteDuration.
        // all vehicles start at the depot, and end their day at the depot.
        // Each ride stop must be reached before a given time (window_end) by a vehicle.
        // use distance() to compute the distance between two points.

        // WARNING: this function should only be used for debugging purposes, to check that you find all solutions
        // to a given instance
        // it is not mandatory in the project and can be left as it is: it has no impact on the grading
        // (but is still useful for debugging!)
        //TODO 1.0
        final int totalSolution = 2000;
        LinkedList<DialARideSolution> solutions = new LinkedList<>();


        //TODO 1.1
        //Define the number of nodes
        int number_of_start_depots = nVehicles; //A depot for each vehicle
        int number_of_end_depots= nVehicles; // A end depot for each vehicles
        int first_end_depot = nVehicles+pickupRideStops.size()+dropRideStops.size(); //index of the first end depot
        int n = pickupRideStops.size() + dropRideStops.size() + number_of_start_depots+number_of_end_depots; //total nodes that must visit only once
        //All the ride
        ArrayList<RideStop> allNodes = new ArrayList<>();
        allNodes.addAll(pickupRideStops);
        allNodes.addAll(dropRideStops);
        for (int i = 0; i < number_of_end_depots; i++) {
            RideStop endDepot = new RideStop();
            endDepot.type=0;
            endDepot.pos_x=depot.pos_x;
            endDepot.pos_y=depot.pos_y;
            endDepot.window_end=maxRouteDuration;
            allNodes.add(depot);
        }

        //TODO 2.1
        //Create the solver
        Solver cp = makeSolver();

        //Create the variables
        IntVar[] succ = makeIntVarArray(cp, n, n); //succ[i] is the successor node of i
        IntVar[] pred = makeIntVarArray(cp,n,n); //pred[i] is the predecessor node of i
        IntVar[] distSucc = makeIntVarArray(cp, n, 0, maxRideTime); //distSucc[i] is the distance between node i and its successor
        IntVar[] time = makeIntVarArray(cp, n, maxRouteDuration+1); //time[i] is the time when the vehicle visit the node i

        IntVar[] peopleOn = makeIntVarArray(cp, n, vehicleCapacity+1); //peopleOn[i] is the number of people on the vehicle when it visit the node i
        BoolVar FALSE=makeBoolVar(cp);
        FALSE.fix(false);
        for (int i = 0; i < peopleOn.length; i++) {
            cp.post(lessOrEqual(peopleOn[i],vehicleCapacity));
            cp.post(new IsLessOrEqual(FALSE,peopleOn[i],-1)); //the veichle capacity must be at least 0
        }
        IntVar index[] = makeIntVarArray(cp, n, n); //index[i] is the position of the node i in the path (so index[i]=i)
        for (int i = 0; i < n; i++) {
            cp.post(equal(index[i], i));
        }
        IntVar[] VisitedByVehicle = makeIntVarArray(cp, n, nVehicles); //VisitedByVehicle[i] is the vehicle that visit the node i


        //managedBy[task]= vehicle that manage the task
        IntVar[] managedBy= makeIntVarArray(cp,pickupRideStops.size(),nVehicles); //IntVar that represent the vehicle that manage the pickup node i

        //TODO 2.2 Circuit constraint
        cp.post(new Circuit(succ));
        cp.post(new Circuit(pred));

        //Channelling between pred and succ
        for (int i = 0; i < n; i++) {
            cp.post(new Element1DVar(pred, succ[i],index[i]));
            cp.post(lessOrEqual(time[i],maxRouteDuration));
        }


        //TODO 2.3 DistanceMatrix
        int[][] distanceMatrix = new int[n][n];
        computeDistanceMatrix(distanceMatrix,pickupRideStops,dropRideStops,depot,nVehicles,number_of_start_depots,number_of_end_depots);

        //TODO 2.4 Time PeopleOn VisitByVehicle at Start and at the End
        //Departure time from the depots
        for (int i = 0; i < nVehicles; i++) {
            cp.post(equal(time[i],0));
            cp.post(lessOrEqual(time[first_end_depot+i],maxRouteDuration));
            cp.post(equal(peopleOn[i],0));
            cp.post(equal(peopleOn[first_end_depot+i],0));

            cp.post(equal(VisitedByVehicle[i],i));
            //cp.post(equal(VisitedByVehicle[first_end_depot+i],(i+1)%nVehicles));

            //cp.post(new Element1DVar(VisitedByVehicle,succ[i],VisitedByVehicle[i]));
            //cp.post(new Element1DVar(VisitedByVehicle,pred[first_end_depot+i],VisitedByVehicle[first_end_depot+i]));
            //cp.post(new Element1DVar(VisitedByVehicle,succ[first_end_depot+i],VisitedByVehicle[i]));
            //cp.post(equal(succ[first_end_depot+i],i)); //the end depot is the last node visited



            for (int pickupNode = nVehicles; pickupNode < n-nVehicles-dropRideStops.size() ; pickupNode++) {
                int dropNode = pickupNode+pickupRideStops.size();
                //the pred of a depot couldnt be a pickup
                cp.post(notEqual(pred[first_end_depot+i],pickupNode));
                //the succ of a depot couldnt be a drop
                cp.post(notEqual(succ[i],dropNode));
            }


            //The vehicle must be back at the end depot from a node that have only 1 person




            //Update distance from successor
            cp.post(new Element1D(distanceMatrix[i],succ[i],distSucc[i]));
            //update time
            //time[succ[i]] = time[i] + distSucc[i] OR time[i]=time[pred[i]]+distSucc[pred[i]]
            cp.post(new Element1DVar(time,succ[i],sum(time[i],distSucc[i])));
            //update peopleOn
            //peopleOn[succ[i]] = peopleOn[i], in this case, depot is not a pickup or drop so must be the same
            cp.post(new Element1DVar(peopleOn,succ[i],peopleOn[i]));
            cp.post(new Element1DVar(peopleOn,pred[first_end_depot+i],plus(peopleOn[first_end_depot+i],1)));
            cp.post(new Element1DVar(managedBy,minus(succ[i],nVehicles),VisitedByVehicle[i]));
            cp.post(new Element1DVar(managedBy,minus(pred[first_end_depot+i],nVehicles+pickupRideStops.size()),VisitedByVehicle[first_end_depot+i]));

            //cp.post(equal(succ[first_end_depot+i],(i+1)%nVehicles));


        }


        //TODO 2.5 Time PeopleOn VisitByVehicle at the Pickup and Drop nodes
        for (int i = nVehicles; i <n ; i++) {

            //THE DISTANCE FROM THE CURRENT NODE TO THE DROP NODE OF EACH TASK MANAGED BY VEHICLE IN THIS NODE
            //MUST BE LESS THAN THE MAXRIDE TIME AND window_end
            //time[i]+distanceMatrix[i][succ[i]]<=time[pickupnode task]+maxRideTime
            //time[i]+distanceMatrix[i][succ[i]]<=window_end[pickupnode task]
            //ok how to do this?
            //I need to find the pickup node that is managed by the vehicle that visit the current node
            //in managedBy there is the vehicle that manage the pickup node i
            //so i need to find the pickup node that is managed by the vehicle that visit the current node
            //and then check if the distance from the current node to the drop node of the task is less than the maxRideTime
            //and the window_end of the task
            //let's do this
            //Take the vehicle that visit the current node



            //pickup
            if(i<nVehicles+pickupRideStops.size()){
                //Update distance from successor
                cp.post(new Element1D(distanceMatrix[i],succ[i],distSucc[i]));
                //time[succ[i]] = time[i] + distSucc[i] OR time[i]=time[pred[i]]+distSucc[pred[i]]
                cp.post(new Element1DVar(time,succ[i],sum(time[i],distSucc[i])));

                cp.post(lessOrEqual(time[i],maxRouteDuration));
                //peopleOn[succ[i]] = peopleOn[i]+1, in this case, depot is a pickup so one more person
                cp.post(new Element1DVar(peopleOn,succ[i],plus(peopleOn[i],1)));
                //VisitedByVehicle[succ[i]]=VisitedByVehicle[i]
                cp.post(new Element1DVar(VisitedByVehicle,succ[i],VisitedByVehicle[i]));
                cp.post(new Element1DVar(VisitedByVehicle,pred[i],VisitedByVehicle[i]));
                cp.post(new Element1DVar(VisitedByVehicle,index[i+pickupRideStops.size()],VisitedByVehicle[i]));

                //Update IntVar managedBy for search
                cp.post(new Element1DVar(managedBy,minus(index[i],nVehicles),VisitedByVehicle[i]));
                cp.post(lessOrEqual(time[i],pickupRideStops.get(i-nVehicles).window_end));

                //Rendundant constraints to shrink the search space
                //The nexnode of a pickup node cant'be a depot
                for (int j = 0; j < nVehicles; j++) {
                    cp.post(notEqual(succ[i],first_end_depot+j));
                }

                //The difference time between the pickup node and the drop node must be less than the maxRideTime
                //time[dropNode]-time[curr]<=maxRideTime
                //time[dropNode]<=time[curr]+maxRideTime
                cp.post(largerOrEqual(time[i],minus(time[i+pickupRideStops.size()],maxRideTime)));


                int dropNode = i+pickupRideStops.size();
                cp.post(lessOrEqual(time[i],time[dropNode]));
                cp.post(notEqual(pred[i],dropNode));
                cp.post(notEqual(succ[dropNode],i));




            }

            //drop
            else if (i>=nVehicles+pickupRideStops.size() && i<nVehicles+pickupRideStops.size()+dropRideStops.size()){
                //Update distance from successor
                cp.post(new Element1D(distanceMatrix[i],succ[i],distSucc[i]));
                //time[succ[i]] = time[i] + distSucc[i] OR time[i]=time[pred[i]]+distSucc[pred[i]]
                cp.post(new Element1DVar(time,succ[i],sum(time[i],distSucc[i])));

                //The vehicle must be arrived before the end of the window
                cp.post(lessOrEqual(time[i],dropRideStops.get(i-nVehicles-pickupRideStops.size()).window_end));
                //The vehicle must be arrived before the maxRideTime
                cp.post(lessOrEqual(time[i],plus(time[i-pickupRideStops.size()],maxRideTime)));
                //peopleOn[succ[i]] = peopleOn[i]+1, in this case, depot is a drop so one more person
                cp.post(new Element1DVar(peopleOn,succ[i],minus(peopleOn[i],1)));

                //VisitedByVehicle[succ[i]]=VisitedByVehicle[i]
                cp.post(new Element1DVar(VisitedByVehicle,succ[i],VisitedByVehicle[i]));
                //The vehicle that visit the drop node must be the same that visit the pickup node
                //cp.post(equal(VisitedByVehicle[i],VisitedByVehicle[i-pickupRideStops.size()]));
                cp.post(new Element1DVar(VisitedByVehicle,index[i],VisitedByVehicle[i-pickupRideStops.size()]));
                cp.post(new Element1DVar(VisitedByVehicle,pred[i],VisitedByVehicle[i]));

                //The vehicle must no exceed the maxRouteDuration
                cp.post(lessOrEqual(time[i],maxRouteDuration));

                //Rendundant constraints to shrink the search space
                //The drop node must be visited after the pickup node
                int pickupNode = i-pickupRideStops.size();
                cp.post(lessOrEqual(time[pickupNode],time[i]));



            }

        }

        IntVar totalDist = sum(distSucc);
        Objective obj_function=cp.minimize(totalDist);

        DFSearch dfs= makeDfs(cp, ()-> {
            //Branching
            //First select the candidate vehicle
            //Then select the nearest node

            IntVar xs =selectMin(succ,
                    xi -> xi.size() > 1,
                    IntVar::size);
            if(xs==null) return EMPTY; //All Variable are fixed

            int selected = IntStream.range(0, succ.length).filter(i -> succ[i] == xs).findFirst().orElse(-1);


            // Create a list of all possible successors with their distances
            int[] nearestNodes = new int[xs.size()];
            xs.fillArray(nearestNodes);

            System.out.println("Nearest Nodes: "+Arrays.toString(nearestNodes));
            System.out.println("I pickup vanno da "+nVehicles+" a "+(nVehicles+pickupRideStops.size()));
            System.out.println("I drop vanno da "+(nVehicles+pickupRideStops.size())+" a "+(nVehicles+pickupRideStops.size()+dropRideStops.size()));

            List<Integer> nearestNodes_filtered = Arrays.stream(nearestNodes)
                    //  .filter(i -> heuristic(selected, i, nVehicles,vehicleCapacity, pickupRideStops,
                    //            dropRideStops,managedBy, peopleOn, distanceMatrix, maxRouteDuration,
                    //        maxRideTime, time,VisitedByVehicle,pred,succ))
                    .boxed().collect(Collectors.toList());
            /*
            System.out.println("Nearest Nodes Filtered: "+nearestNodes_filtered.toString());
            //Assign to each possible node a value and then order with this value
            Map<Integer,Integer> costToNode = new HashMap<>();
            for (int i = 0; i < nearestNodes_filtered.size(); i++) {
                int curr_node = nearestNodes_filtered.get(i);
                int value=0;
                //update the value with the distance
                value+= (distanceMatrix[selected][curr_node]);
                //update the value with the window_end
                if(curr_node>=nVehicles && curr_node<nVehicles+pickupRideStops.size()){
                    value*=(allNodes.get(curr_node).window_end-time[selected].min());
                    //count the number of vehicle that are near
                    value/=4;

                }
                if(curr_node>=nVehicles+pickupRideStops.size() && curr_node<nVehicles+pickupRideStops.size()+dropRideStops.size()){
                    value*=(allNodes.get(curr_node).window_end-time[selected].min());
                }
                if(curr_node>=nVehicles+pickupRideStops.size()+dropRideStops.size()){
                    System.out.println("The vehicle"+VisitedByVehicle[selected].min()+" is back to the depot");
                    value=Integer.MAX_VALUE;
                }
                costToNode.put(curr_node,value);
            }
            System.out.println("Nearest Nodes Filtered: "+nearestNodes_filtered.toString());
            System.out.println("Vehicle Size"+vehicleCapacity);
            System.out.println("Vehicle: "+nVehicles);

            System.out.println("Cost: "+Arrays.toString(costToNode.entrySet().toArray()));
            //order the nodes by the value
            nearestNodes_filtered.sort(Comparator.comparingInt(costToNode::get));
            System.out.println("Nearest Nodes sorted by cost: "+nearestNodes_filtered.toString());
            System.out.println("VisitedByVehicle: "+VisitedByVehicle[selected]);
            System.out.println("Time: "+time[selected].min());
            System.out.println("Pred:"+pred[selected]);
            System.out.println("Node: "+selected);
            System.out.println("MaxRouting Time: "+maxRouteDuration);
               */

            if(nearestNodes_filtered.size()==0) {
                throw  InconsistencyException.INCONSISTENCY;
            }
            //go to the node with the minium window_end that i'm the only one that can manage it
            int best= nearestNodes_filtered.get(0);

            return branch(() -> {
                        try {
                            xs.getSolver().post(equal(xs, best));
                        } catch (InconsistencyException e) {
                            throw e;
                        }
                    }, () -> {
                        try {
                            xs.getSolver().post(notEqual(xs, best));

                        } catch (InconsistencyException e) {
                            throw e;
                        }
                    }

            );
        });



        //lns
        int failureLimit = 25;
        Random rand = new Random(0);
        final long onesec = (long) 1e+9;
        final long onemin = 60*onesec;
        final long maxTime = 6*onemin - 1*onesec;
        final long remplissage = 85;
        int maxFailLimit = 60;
        int minFailLimit = 10;
        AtomicInteger acc= new AtomicInteger();
        final int[] bestPath = new int[n];
        final int[] bestRideID = new int[n];


        //TODO 2.7 ACTION ON SOLUTION
        AtomicInteger curr_solution= new AtomicInteger();
        dfs.onSolution(() -> {
            acc.getAndIncrement();
            DialARideSolution curr=
                    new DialARideSolution(nVehicles, pickupRideStops, dropRideStops, depot, vehicleCapacity, maxRideTime, maxRouteDuration);
            System.out.println("solution: "+totalDist.min());
            System.out.println("Max Routing Time: "+maxRouteDuration);
            for (int i = 0; i < n; i ++) bestPath[i] = succ[i].min();
            for (int i = 0; i < n; i ++) bestRideID[i] = VisitedByVehicle[i].min();
            System.out.println("Best path: "+Arrays.toString(bestPath));
            System.out.println("Best ride ID: "+Arrays.toString(bestRideID));


            for (int i = 0; i < nVehicles; i++) {
                System.out.println("Vehicle "+i);
                int current = i;
                StringBuilder path = new StringBuilder();
                StringBuilder vehicleRideID = new StringBuilder();
                StringBuilder timeString = new StringBuilder();
                StringBuilder sizeString = new StringBuilder();
                while (current < nVehicles + pickupRideStops.size() + dropRideStops.size()) {
                    path.append(current+",");
                    vehicleRideID.append(bestRideID[current]+",");
                    timeString.append(time[current].min()+",");
                    sizeString.append(peopleOn[current].min()+",");
                    current = bestPath[current];
                }
                path.append(bestPath[current]);
                vehicleRideID.append(bestRideID[current]);
                timeString.append(time[current].min());
                sizeString.append(peopleOn[current].min());
                System.out.println("Time: "+timeString.toString());
                System.out.println("Path: "+path.toString());
                System.out.println("Ride ID: "+vehicleRideID.toString());
                System.out.println("Size: "+sizeString.toString());

            }


            final int idx_sol= curr_solution.get();
            System.out.println("I pickup vanno da "+nVehicles+" a "+(nVehicles+pickupRideStops.size()));
            System.out.println("I drop vanno da "+(nVehicles+pickupRideStops.size())+" a "+(nVehicles+pickupRideStops.size()+dropRideStops.size()));
            for (int i = 0; i < nVehicles; i++) {
                int curr_node = i;
                while (curr_node < nVehicles + pickupRideStops.size() + dropRideStops.size()) {
                    int succ_node=bestPath[curr_node];
                    boolean isPickup = succ_node>=nVehicles && succ_node<nVehicles+pickupRideStops.size();
                    if(succ_node>=nVehicles+pickupRideStops.size()+dropRideStops.size()){
                        //The veichle is back to the depot
                        curr_node = succ_node;
                        break;
                    }
                    if(isPickup) {
                        curr.addStop(bestRideID[curr_node],succ_node-nVehicles,isPickup);
                    }
                    else {
                        curr.addStop(bestRideID[curr_node],succ_node-pickupRideStops.size()-nVehicles,isPickup);
                    }

                    curr_node = succ_node;
                }
            }
            solutions.add(curr);
            curr_solution.getAndIncrement();

        });

        SearchStatistics stats = dfs.solve(statistics -> statistics.numberOfSolutions() ==2000);
        System.out.format("#Solutions: %s\n", stats.numberOfSolutions());
        System.out.format("Statistics: %s\n", stats);





        return solutions;
    }

    /**
     * Returns the distance between two ride stops
     */
    public static int distance(RideStop a, RideStop b) {
        return (int) (Math.sqrt((a.pos_x - b.pos_x) * (a.pos_x - b.pos_x) + (a.pos_y - b.pos_y) * (a.pos_y - b.pos_y)) * 100);
    }

    /**
     * A solution. To create one, first do new DialARideSolution, then
     * add, for each vehicle, in order, the pickup/drops with addStop(vehicleIdx, rideIdx, isPickup), where
     * vehicleIdx is an integer beginning at 0 and ending at nVehicles - 1, rideIdx is the id of the ride you (partly)
     * fullfill with this stop (from 0 to pickupRideStops.size()-1) and isPickup a boolean indicate if you are beginning
     * or ending the ride. Do not add the last stop to the depot, it is implicit.
     * <p>
     * You can check the validity of your solution with compute(), which returns the total distance, and raises an
     * exception if something is invalid.
     * <p>
     * DO NOT MODIFY THIS CLASS.
     */
    public static class DialARideSolution {
        public ArrayList<Integer>[] stops;
        public ArrayList<RideStop> pickupRideStops;
        public ArrayList<RideStop> dropRideStops;
        public RideStop depot;
        public int capacity;
        public int maxRideTime;
        public int maxRouteDuration;

        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append("Length: ");
            b.append(compute());
            b.append("\n");
            for (int i = 0; i < stops.length; i++) {
                b.append("- ");
                for (int s : stops[i]) {
                    if (s >= pickupRideStops.size()) {
                        b.append(s - pickupRideStops.size());
                        b.append("d, ");
                    } else {
                        b.append(s);
                        b.append("p, ");
                    }
                }
                b.append("\n");
            }
            return b.toString();
        }

        public DialARideSolution(int nVehicles, ArrayList<RideStop> pickupRideStops, ArrayList<RideStop> dropRideStops,
                                 RideStop depot, int vehicleCapacity, int maxRideTime, int maxRouteDuration) {
            stops = new ArrayList[nVehicles];
            for (int i = 0; i < nVehicles; i++)
                stops[i] = new ArrayList<>();

            this.pickupRideStops = pickupRideStops;
            this.dropRideStops = dropRideStops;
            this.depot = depot;
            this.capacity = vehicleCapacity;
            this.maxRideTime = maxRideTime;
            this.maxRouteDuration = maxRouteDuration;
        }

        /**
         * Add a stop on the path of a vehicle
         * No need to add the last stop to the depot, it is implicit
         *
         * @param vehicleId id of the vehicle where the stop occurs
         * @param rideId    id of the ride related to the stop
         * @param isPickup  true if the point is the pickup of the ride, false if it is the drop
         */
        public void addStop(int vehicleId, int rideId, boolean isPickup) {
            stops[vehicleId].add(rideId + (isPickup ? 0 : pickupRideStops.size()));
        }

        public int compute() {
            int totalLength = 0;
            HashSet<Integer> seenRides = new HashSet<>();

            for (int vehicleId = 0; vehicleId < stops.length; vehicleId++) {
                HashMap<Integer, Integer> inside = new HashMap<>();
                RideStop current = depot;
                int currentLength = 0;
                for (int next : stops[vehicleId]) {
                    RideStop nextStop;
                    if (next < pickupRideStops.size())
                        nextStop = pickupRideStops.get(next);
                    else
                        nextStop = dropRideStops.get(next - pickupRideStops.size());

                    currentLength += distance(current, nextStop);

                    if (next < pickupRideStops.size()) {
                        if (seenRides.contains(next))
                            throw new RuntimeException("Ride stop visited twice");
                        seenRides.add(next);
                        inside.put(next, currentLength);
                    } else {
                        if (!inside.containsKey(next - pickupRideStops.size()))
                            throw new RuntimeException("Drop before pickup");
                        if (inside.get(next - pickupRideStops.size()) + maxRideTime < currentLength)
                            throw new RuntimeException("Ride time too long");
                        inside.remove(next - pickupRideStops.size());
                    }

                    if (currentLength > nextStop.window_end)
                        throw new RuntimeException("Ride stop visited too late");
                    if (inside.size() > capacity)
                        throw new RuntimeException("Above maximum capacity");

                    current = nextStop;
                }

                currentLength += distance(current, depot);

                if (inside.size() > 0)
                    throw new RuntimeException("Passenger never dropped");
                if (currentLength > maxRouteDuration)
                    throw new RuntimeException("Route too long");

                totalLength += currentLength;
            }

            if (seenRides.size() != pickupRideStops.size())
                throw new RuntimeException("Some rides never fulfilled");

            return totalLength;
        }
    }

    static class RideStop {
        public float pos_x;
        public float pos_y;
        public int type; //0 == depot, 1 == pickup, -1 == drop
        public int window_end;
    }

    public static RideStop readRide(InputReader reader) {
        try {
            RideStop r = new RideStop();
            reader.getInt(); //ignored
            r.pos_x = Float.parseFloat(reader.getString());
            r.pos_y = Float.parseFloat(reader.getString());
            reader.getInt(); //ignored
            r.type = reader.getInt();
            reader.getInt(); //ignored
            r.window_end = reader.getInt() * 100;
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
        // Reading the data

        //TODO change file to test the various instances.
        InputReader reader = new InputReader("data/dialaride/training");

        int nVehicles = reader.getInt();
        reader.getInt(); //ignore
        int maxRouteDuration = reader.getInt() * 100;
        int vehicleCapacity = reader.getInt();
        int maxRideTime = reader.getInt() * 100;

        RideStop depot = null;
        ArrayList<RideStop> pickupRideStops = new ArrayList<>();
        ArrayList<RideStop> dropRideStops = new ArrayList<>();
        boolean lastWasNotDrop = true;
        while (true) {
            RideStop r = readRide(reader);
            if (r == null)
                break;
            if (r.type == 0) {
                assert depot == null;
                depot = r;
            } else if (r.type == 1) {
                assert lastWasNotDrop;
                pickupRideStops.add(r);
            } else { //r.type == -1
                lastWasNotDrop = false;
                dropRideStops.add(r);
            }
        }
        assert depot != null;
        assert pickupRideStops.size() == dropRideStops.size();

        DialARideSolution sol = solve(nVehicles, maxRouteDuration, vehicleCapacity, maxRideTime, pickupRideStops, dropRideStops, depot);
        System.out.println(sol);
    }
}
