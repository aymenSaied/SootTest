/*
 * InvokeStaticInstrumenter inserts count instructions before
 * INVOKESTATIC bytecode in a program. The instrumented program will
 * report how many static invocations happen in a run.
 * 
 * Goal:
 *   Insert counter instruction before static invocation instruction.
 *   Report counters before program's normal exit point.
 *
 * Approach:
 *   1. Create a counter class which has a counter field, and 
 *      a reporting method.
 *   2. Take each method body, go through each instruction, and
 *      insert count instructions before INVOKESTATIC.
 *   3. Make a call of reporting method of the counter class.
 *
 * Things to learn from this example:
 *   1. How to use Soot to examine a Java class.
 *   2. How to insert profiling instructions in a class.
 */

/* InvokeStaticInstrumenter extends the abstract class BodyTransformer,
 * and implements <pre>internalTransform</pre> method.
 */ 
import soot.*;
import soot.JastAddJ.IfStmt;
import soot.jimple.*;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.util.*;
import soot.jimple.internal.JThrowStmt;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;

import soot.dava.toolkits.base.AST.structuredAnalysis.*;
import soot.toolkits.scalar.*;

public class InvokeStaticInstrumenterP2 extends BodyTransformer{

  /* some internal fields */
  
  static PrintWriter out;
  static PrintWriter patternDistributionOverMethod  ;
  static PrintWriter detectedPattern  ;
  static HashMap<String, Integer> patternDistributionOverClasses; 
  static SimpleLocalDefsUsingParameter  simpleLocalDefs ;  
  static  ArrayList<Local> methodParameterChain;

  static {
	  patternDistributionOverClasses=new HashMap<String, Integer>(350);

  }

  
  public InvokeStaticInstrumenterP2(PrintWriter pw1 ,PrintWriter pw2 ,PrintWriter pw3){
	  
	  out=pw1;
	  patternDistributionOverMethod=pw2;
	  detectedPattern=pw3;
	  
	  patternDistributionOverMethod.println("class name"+";;"+"Method name"+";;"+"number of detected pattern");
	  
	  detectedPattern.println("class name"+";;"+"Method name"+";;"+";;"+"Method signature"+";;"+";;"+";;"+"exit stmt" +";;"+"Analysed unit");  }
  /* internalTransform goes through a method body and inserts 
   * counter instructions before an INVOKESTATIC instruction
   */
  protected void internalTransform(Body body, String phase, Map options) {
    // body's method
    
	  
	  
	  SootMethod method = body.getMethod();
	  SootClass declaringClass =method.getDeclaringClass();
     System.out.println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");

     System.out.println("instrumenting method : " + method.getSignature());
    
    
     Integer nbOfDetectedpatternInCurrentMethod =0;
     
     
     //initialisation du hashmap pour la distrubution des patron sur les classes 
     if (patternDistributionOverClasses.containsKey(declaringClass.getName())) {
     	
 		//ne rien faire 
 	} else {
 		
 		patternDistributionOverClasses.put(declaringClass.getName(),0);

 	}
     
     
     UnitGraph cfg = new ExceptionalUnitGraph(body);
     simpleLocalDefs = new SimpleLocalDefsUsingParameter(cfg);
    
     methodParameterChain =  new ArrayList<Local>();
     
     for (int j = 0; j < method.getParameterCount(); j++) {
 		
     	methodParameterChain.add(body.getParameterLocal(j));
     	    	  	
 	}
     
     
     
    for (Unit unit : cfg) {
		System.out.println("------unit-----> "+ unit);
	}
    
    
    Iterator<Unit> Units= cfg.iterator();
    
    Boolean throwStmtExistInBody =false;
    Boolean ifStmtExistInBody =false;
    
    while (Units.hasNext()&& (!throwStmtExistInBody || !ifStmtExistInBody)) {
		Unit unit = (Unit) Units.next();
		
		if (unit instanceof soot.jimple.IfStmt) {
			
			ifStmtExistInBody= true;	
			
		}
		
		if (unit instanceof ThrowStmt) {
			
			
			throwStmtExistInBody= true;
			
		}
		
	}
    
    
    if (throwStmtExistInBody && ifStmtExistInBody) {
    	
    	System.out.println("la methode iligible----> "+method.getSignature() );
    	   
    	nbOfDetectedpatternInCurrentMethod=this.Pattern2Detecor(cfg);
    	
		
	}
    
    

    
    if (nbOfDetectedpatternInCurrentMethod > 0) {
   	 
        
   	 Integer nbOfDetectedpatternIndeclaringClass = patternDistributionOverClasses.get(declaringClass.getName());
   	 
   	 nbOfDetectedpatternIndeclaringClass += nbOfDetectedpatternInCurrentMethod;
   	 
   	 patternDistributionOverClasses.put(declaringClass.getName(), nbOfDetectedpatternIndeclaringClass);
		
	}    
   
    
    
  }
  
  
  protected  int Pattern2Detecor(UnitGraph cfg) {

	  Boolean EligibleIfStmt ;
	int nbOfDetectedpatternInMethod=0;
	  for (Unit unit : cfg) {
		  
		
		  EligibleIfStmt=false;
		  
		 if (unit instanceof soot.jimple.IfStmt) {
			 
			 Unit targeSucessorOfStmtIf = null;  		   		
	    		
			 Unit theOtherSucessorOfStmtIf  = null;		

			 EligibleIfStmt= this.detcetEligibleIfStmt((soot.jimple.IfStmt)unit,true);
			 
	       	  
		     targeSucessorOfStmtIf = ((soot.jimple.IfStmt) unit).getTarget();
		     
		     List<Unit> successorListOfUnit = cfg.getSuccsOf(unit);
				

		  	 for (Unit sucessorunit : successorListOfUnit) {
		  		 
		  		 if (!sucessorunit.equals(targeSucessorOfStmtIf)) {
		  			 
		  			 theOtherSucessorOfStmtIf =sucessorunit;
		  			 
		  		 }
		  		 
		  	 }
		 
			 
			 if (EligibleIfStmt) {
				 
				 //int PathLength=0;
				// nbOfDetectedpatternInCurrentMethod++;
				 
				 
				 Boolean findTrowStmtaftertargeSucessor;
				 Boolean findTrowStmtaftertheOtherSucessor; 
				 
				 findTrowStmtaftertargeSucessor	= findPathToWhileStmt( cfg,  targeSucessorOfStmtIf,  unit);
				 
				findTrowStmtaftertheOtherSucessor = findPathToWhileStmt( cfg,  theOtherSucessorOfStmtIf,  unit);
				 
				 //faire un autr teste sur les chemin 
				
				if (findTrowStmtaftertargeSucessor &&  !findTrowStmtaftertheOtherSucessor) {
					
					System.out.println("##############------------------>patron detecter a travers targeSucessor pour "+ unit);
					nbOfDetectedpatternInMethod++;
				} else if(!findTrowStmtaftertargeSucessor &&  findTrowStmtaftertheOtherSucessor) {
					
					System.out.println("##############------------------>patron detecter a travers theOtherSucessor pour "+ unit);
					nbOfDetectedpatternInMethod++;

				} 
				
				
			}
			 
			
		}
		 
		
	}
	  
	  return nbOfDetectedpatternInMethod;
	  
	  
	  
	  
  }

  protected Boolean detcetEligibleIfStmt(soot.jimple.IfStmt unitOfTheBodyMethod , boolean localfromparamconsidered){
	  
	  if (localfromparamconsidered) {
			//on fait return true pour que tout les condition sur les variable soit considerer

		return true;
	}else{
	  
	  
  
		  
		  List<ValueBox> vb = unitOfTheBodyMethod.getUseBoxes();
      	  
  		  for (ValueBox valueBox : vb) {
      		        		  
      		  	if (methodParameterChain.contains(valueBox.getValue())) {
      		  		
      		  		return true;
      		  	}
      		        		  
      	  }
    		
		  
		  
		  
		  
	  

		
		
		ConditionExpr Condtionexpr =(ConditionExpr)((soot.jimple.IfStmt) unitOfTheBodyMethod).getCondition();
		
		if (Condtionexpr.getOp1() instanceof Local){
			
			
		List<Unit>	dedofAt =simpleLocalDefs.getDefsOfAt((Local)Condtionexpr.getOp1(), unitOfTheBodyMethod);
		
			if (dedofAt.size()> 0) {
				
				return true;
				
			} 
			
			else {

				return false;
			}
	  
	  
		} else {
			
			//on fait return false pour que seul les condition sur les variable initialise � partr de parametre et sur les parametre  soit considerer 
			return false;

		}
	  
	  
	}
	  
  }
  
  
  protected Boolean findPathToWhileStmt(UnitGraph cfg, Unit theConsideredSucessor, Unit ifstmt){
	  

		 int PathLength=0;
		 Boolean findThroStmt=false;
		 Boolean mustExitWhileLoop =false;
		 
		 Body body = cfg.getBody();
		 SootMethod method = body.getMethod();
		 SootClass declaringClass =method.getDeclaringClass();
		 List<Unit> exitpointlist=cfg.getTails();


		 
		 
		 
		 
		 
		 
		 ArrayList<Unit> beginigUnitOfTrap =new ArrayList<Unit>();
		 ArrayList<Unit> HandlerUnitOfTrap =new ArrayList<Unit>();
		    Chain<Trap> trap =body.getTraps();
			 for (Trap trap2 : trap) {
				 beginigUnitOfTrap.add( trap2.getBeginUnit());
				 System.out.println("______trap  ___"+trap2);
				 HandlerUnitOfTrap.add(trap2.getHandlerUnit());
			}
		 
		 
	  while (PathLength < 4 && !findThroStmt && !mustExitWhileLoop) {
			
		  System.out.println(PathLength+"_____path ___"+theConsideredSucessor);
			if (theConsideredSucessor instanceof ThrowStmt ) {
				
				findThroStmt=true;
				 System.out.println(">>>>>--------on a trouver le throw stmt qui suit le if stmt donc detection du pattern----path: "+PathLength+"--->>>>   "+theConsideredSucessor  +" suit " + ifstmt  );
				 
				
				  detectedPattern.println(declaringClass+";;"+method.getName()+";;"+";;"+method.getSignature()+";;"+";;"+";;"+theConsideredSucessor +";;"+ifstmt);

				 
				 
			}else if (exitpointlist.contains(theConsideredSucessor)) {
				
				mustExitWhileLoop=true;
				 System.out.println(">>>>>--------+on a trouver exitpoint suit le if stmt mais  cet exit stmt n'est pas immediatemet � la suit du if  donc on ne dois pas le consid�rer------->>>>   "+theConsideredSucessor +" suit " + ifstmt);
				//Todo faire la separation entre deux cas celui ou la PathLength< 2 donc posibilit� de patern detecter mais pas � 100%
			
			}else if (theConsideredSucessor instanceof soot.jimple.IfStmt) {
				
				//todo prendre en consideration les condition composer qui engendre des if ibriquer 
				 System.out.println(">>>>>----erreur----on ne prend pas en consideration les condition composer qui engendre des if ibriquer ------->>>>   " + theConsideredSucessor);
				
				//on dois sortire du while sans faire return pour paser � la prochaine ifstmt du cfg 
				mustExitWhileLoop= true;
				//toDo lorsque on traite les les condition composer on enleve mustExitWhileLoop et on la remplace par une detection de patorn 
				
			}else{
				
				
				List<Unit> succesorlist =cfg.getSuccsOf(theConsideredSucessor);
				if (succesorlist.size()== 0) {
					 System.out.println(">>>>>--------pas de succesor  ------->>>>   "+ theConsideredSucessor);
					mustExitWhileLoop= true;//on dois sortire du while sans faire return pour paser � la prochaine ifstmt du cfg 
				} else if (succesorlist.size()== 1) {
					
					if(HandlerUnitOfTrap.contains(theConsideredSucessor)){
						mustExitWhileLoop= true;
						System.out.println(">>>>>--------beginig of try catch   ------->>>>   "+ theConsideredSucessor);
						
					}else{
						
						theConsideredSucessor=succesorlist.get(0);
						PathLength++;	
						
					}
					
					
					
				}else if (succesorlist.size() > 1) {
					
					 System.out.println(">>>>>--------succesorlist.size() > 1  unhandled case  ------->>>>   "+ theConsideredSucessor);
					mustExitWhileLoop= true;//on dois sortire du while sans faire return pour paser � la prochaine ifstmt du cfg
					
					
					for (Unit unit : succesorlist) {
						System.out.println(unit);
					}
					
					
				}
				
				
			}     			
			
			
		}
	  
	  return findThroStmt;
  }

  
  public void statistique() throws FileNotFoundException{
		
		PrintWriter patternDistributionOverClass= new PrintWriter(".\\statistique\\P2\\JHotDraw7.0.6\\patternDistributionOverClass.csv");  
		
		patternDistributionOverClass.println("class name"+";;"+"number of detected pattern");

		int i =0;
		int j=0; 
		System.out.println("___________________Statistique________________");

		
	Set<java.util.Map.Entry<String, Integer>> setEntry = patternDistributionOverClasses.entrySet();

	for (java.util.Map.Entry<String, Integer> entry : setEntry) {
		
		
		
		System.out.println(j+"la clase ---> "+entry.getKey()+" nbpatern ----> "+entry.getValue());
		patternDistributionOverClass.println(entry.getKey()+";;"+entry.getValue());

		i+=entry.getValue();
		j++;
	}

	System.out.println(" ___________________Fin Statistique_______________avec nb totl de patron :_"+i);

	patternDistributionOverClass.close();
	} 

  
  
  
  
}


