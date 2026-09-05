# HTTP and ownership probes

Recorded review harness, not shipped product code. See [EVIDENCE.md](../EVIDENCE.md) for setup,
proof limits and expected observations. Java blocks are retained as runnable review material so
future implementation tasks can convert the counterexamples into normal regression fixtures.

## HttpRuntimeProbe.java

~~~java
package rs.slingshot.agent.http;
import java.lang.reflect.*;
import javax.jcr.*;
import org.apache.sling.testing.mock.sling.context.SlingContextImpl;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.servlethelpers.*;
import rs.slingshot.agent.contract.*;
import rs.slingshot.agent.execution.*;
import rs.slingshot.agent.store.*;
import rs.slingshot.agent.json.*;

public class HttpRuntimeProbe {
 static final AgentContract CONTRACT=((AgentContract.Loaded)AgentContract.load()).contract();
 static class Fixture implements AutoCloseable {
  final Object test; final SlingContext sling;
  Fixture(Class<?> type) throws Exception {
   var c=type.getDeclaredConstructor(); c.setAccessible(true);test=c.newInstance();
   var f=type.getDeclaredField("sling"); f.setAccessible(true);sling=(SlingContext)f.get(test);
   var setup=SlingContextImpl.class.getDeclaredMethod("setUp");setup.setAccessible(true);setup.invoke(sling);
  }
  Object call(String name,Class<?>[] types,Object...args)throws Exception {return invoke(test,name,types,args);}
  public void close()throws Exception{var m=SlingContextImpl.class.getDeclaredMethod("tearDown");m.setAccessible(true);m.invoke(sling);}
 }
 static Object invoke(Object obj,String name,Class<?>[] types,Object...args)throws Exception{
  var m=obj.getClass().getDeclaredMethod(name,types);m.setAccessible(true);
  try{return m.invoke(obj,args);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
 }
 static class Counting implements SubmitServlet.Commands {
  int runs; public boolean serves(String n){return true;}
  public ExecutionOutcome.Result run(LogicalOperation op,DocumentValue.Mapping submission,Session session){runs++;return new ExecutionOutcome.Inline("{\"sentinel\":\"result-is-durable\"}");}
 }
 public static void main(String[]args)throws Exception {
  try(var f=new Fixture(SubmitServletTest.class)){
   Session s=(Session)f.call("prepared",new Class<?>[]{});
   StatePath.Caller caller=(StatePath.Caller)f.call("caller",new Class<?>[]{});
   long slots=0;while(CapacityLedger.admit(s,AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS,caller,1,CONTRACT) instanceof CapacityLedger.Admitted)slots++;
   Counting commands=new Counting();
   var first=(MockSlingHttpServletResponse)f.call("answering",new Class<?>[]{SubmitServlet.Commands.class,String.class},commands,"a-submission.json");
   for(long i=0;i<slots;i++)CapacityLedger.release(s,AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS,caller,1,CONTRACT);
   var retry=(MockSlingHttpServletResponse)f.call("answering",new Class<?>[]{SubmitServlet.Commands.class,String.class},commands,"a-submission.json");
   var op=(LogicalOperation)f.call("stored",new Class<?>[]{Session.class,String.class},s,"a-submission.json");
   System.out.println("CAPACITY first="+first.getStatus()+" retry="+retry.getStatus()+" runs="+commands.runs+" state="+op.state());
  }
  try(var f=new Fixture(EventStreamServletTest.class)){
   Session s=(Session)f.call("recorded",new Class<?>[]{});
   var response=(MockSlingHttpServletResponse)f.call("asking",new Class<?>[]{String.class,String.class},"with-events-waiting","1:0");
   var identifier=((SubscriptionRecord.Held)SubscriptionRecord.identifier("following-daemon-one",CONTRACT)).identifier();
   System.out.println("STREAM status="+response.getStatus()+" emittedSucceeded="+response.getOutputAsString().contains("event:succeeded")+" highWater="+HighWaterMark.read(s,identifier));
  }
  try(var f=new Fixture(OperationLookupServletTest.class)){
   f.call("recorded",new Class<?>[]{});
   var ident=OperationLookupServletTest.class.getDeclaredMethod("identifier");ident.setAccessible(true);String id=(String)ident.invoke(null);
   try{f.call("lookup",new Class<?>[]{String.class,String.class},id,"9223372036854775808");System.out.println("OVERFLOW unexpectedly returned");}
   catch(NumberFormatException e){System.out.println("OVERFLOW uncaught="+e.getClass().getSimpleName());}
  }
 }
}

~~~

## OwnershipProbe.java

~~~java
package rs.slingshot.agent.http;
import javax.jcr.*;
import javax.jcr.security.*;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.servlethelpers.*;
import rs.slingshot.agent.store.*;
import rs.slingshot.agent.wire.*;
public class OwnershipProbe {
 public static void main(String[]args)throws Exception{
  try(var f=new HttpRuntimeProbe.Fixture(OperationLookupServletTest.class)){
   Session admin=(Session)f.call("recorded",new Class<?>[]{});
   f.call("appended",new Class<?>[]{Session.class,JobEventKind.class},admin,JobEventKind.SUCCEEDED);
   var users=((JackrabbitSession)admin).getUserManager();
   var reader=users.createUser("review-reader","review-password");
   AccessControlManager acm=admin.getAccessControlManager();
   var it=acm.getApplicablePolicies(StatePath.ROOT);
   AccessControlList acl=(AccessControlList)it.nextAccessControlPolicy();
   acl.addAccessControlEntry(reader.getPrincipal(),new Privilege[]{acm.privilegeFromName(Privilege.JCR_READ)});
   acm.setPolicy(StatePath.ROOT,acl);admin.save();
   Session read=admin.getRepository().login(new SimpleCredentials("review-reader","review-password".toCharArray()));
   try{
    ResourceResolver rr=new ResourceResolverWrapper(f.sling.resourceResolver()){
     @Override public String getUserID(){return read.getUserID();}
     @Override public <T>T adaptTo(Class<T> t){return t==Session.class?t.cast(read):super.adaptTo(t);}
    };
    var m=OperationLookupServletTest.class.getDeclaredMethod("identifier");m.setAccessible(true);String id=(String)m.invoke(null);
    var req=new MockSlingHttpServletRequest(rr);req.setMethod("GET");
    ((MockRequestPathInfo)req.getRequestPathInfo()).setResourcePath(OperationLookupServlet.route().path());
    req.setParameterMap(java.util.Map.of(OperationLookupServlet.OPERATION_QUERY_MEMBER,id));
    var res=new MockSlingHttpServletResponse();new OperationLookupServlet().service(req,res);
    System.out.println("OWNERSHIP reader="+read.getUserID()+" adminGroupMember="+(users.getAuthorizable("administrators") instanceof org.apache.jackrabbit.api.security.user.Group g && g.isMember(reader))+" status="+res.getStatus()+" body="+res.getOutputAsString());
   }finally{read.logout();}
  }
 }
}

~~~

## TerminalProbe.java

~~~java
package rs.slingshot.agent.http;
import javax.jcr.*;
import org.apache.sling.servlethelpers.*;
import rs.slingshot.agent.execution.*;
import rs.slingshot.agent.json.*;
import rs.slingshot.agent.store.*;
public class TerminalProbe {
 public static void main(String[]args)throws Exception{
  try(var f=new HttpRuntimeProbe.Fixture(SubmitServletTest.class)){
   Session s=(Session)f.call("prepared",new Class<?>[]{});
   StatePath.Caller caller=(StatePath.Caller)f.call("caller",new Class<?>[]{});
   SubmitServlet.Commands commands=new SubmitServlet.Commands(){
    public boolean serves(String n){return true;}
    public ExecutionOutcome.Result run(LogicalOperation op,DocumentValue.Mapping sub,Session session){
     try{System.out.println("EVENT_QUOTA="+CapacityLedger.admit(session,AccountedQuantity.EVENT_ROWS,caller,AccountedQuantity.EVENT_ROWS.admissibleCallerShare(HttpRuntimeProbe.CONTRACT),HttpRuntimeProbe.CONTRACT));}catch(RepositoryException e){throw new RuntimeException(e);}
     return new ExecutionOutcome.Inline("{\"success\":true}");
    }
   };
   var res=(MockSlingHttpServletResponse)f.call("answering",new Class<?>[]{SubmitServlet.Commands.class,String.class},commands,"a-submission.json");
   var op=(LogicalOperation)f.call("stored",new Class<?>[]{Session.class,String.class},s,"a-submission.json");
   System.out.println("TERMINAL status="+res.getStatus()+" state="+op.state()+" resultPresent="+TerminalCommit.answerIn(s,OperationStore.pathOf(op.identity())).isPresent());
  }
 }
}

~~~
