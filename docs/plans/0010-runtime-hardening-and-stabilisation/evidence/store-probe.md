# Oak contention and interruption probe

Recorded review harness, not shipped product code. See [EVIDENCE.md](../EVIDENCE.md) for setup,
proof limits and expected observations. Java blocks are retained as runnable review material so
future implementation tasks can convert the counterexamples into normal regression fixtures.

## StoreReviewProbe.java

~~~java
import java.lang.reflect.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import javax.jcr.*;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.api.resource.ResourceResolverFactory;
import rs.slingshot.agent.contract.*;
import rs.slingshot.agent.store.*;
import rs.slingshot.agent.continuation.*;
import rs.slingshot.agent.execution.*;
import rs.slingshot.agent.identity.*;
import rs.slingshot.agent.json.*;

public class StoreReviewProbe {
  static Object invoke(Object target, Method method,Object[] args) throws Throwable {
    try { return method.invoke(target,args); } catch(InvocationTargetException e) {throw e.getCause();}
  }
  static Session sessionProxy(Session real, InvocationHandler action) { return (Session)Proxy.newProxyInstance(StoreReviewProbe.class.getClassLoader(),new Class<?>[]{Session.class},action); }
  static OperationIdentity identity(AgentContract contract) throws Exception {
    byte[] bytes=Files.readAllBytes(Path.of("core/src/test/resources/fixtures/maintenance-sweep/operation-0.json"));
    var document=((BoundedDocumentReader.Read)BoundedDocumentReader.read(bytes,BoundedDocumentReader.Bounds.from(contract))).value();
    return ((OperationIdentity.Held)OperationIdentity.of(document,contract)).identity();
  }
  static void walked(Session session,String path) throws Exception { Node n=session.getRootNode(); for(String name:path.substring(1).split("/")) n=n.hasNode(name)?n.getNode(name):n.addNode(name,"nt:unstructured"); session.save(); }
  public static void main(String[] args) throws Exception {
    var context = new SlingContext(ResourceResolverType.JCR_OAK);
    var setup = SlingContext.class.getDeclaredMethod("setUpContext"); setup.setAccessible(true); setup.invoke(context);
    var contract = ((AgentContract.Loaded)AgentContract.load()).contract();
    Session first = context.resourceResolver().adaptTo(Session.class);
    walked(first,StatePath.ROOT);
    var caller = ((StatePath.Held)StatePath.caller("review-caller")).caller();
    var quantity = AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS;
    CapacityLedger.prepare(first, quantity, caller);
    System.out.println("Admission: " + CapacityLedger.admit(first, quantity, caller, 2, contract));
    var factory = context.getService(ResourceResolverFactory.class);
    var resolver = factory.getResourceResolver(Map.of());
    Session second = resolver.adaptTo(Session.class);
    StatePath identical=StatePath.deployment("same-next-cas");
    ClaimByCreation.claim(first,identical,"nt:unstructured",n->{});
    first.getNode(identical.path()).setProperty("count",0L); first.save();
    AtomicBoolean sameInjected=new AtomicBoolean();
    Session identicalRaced=sessionProxy(first,(p,m,a)->{
      if(m.getName().equals("save")&&sameInjected.compareAndSet(false,true))
        System.out.println("Nested identical CAS="+CompareAndSet.set(second,identical,"count",0,1));
      return invoke(first,m,a);
    });
    System.out.println("Outer identical CAS="+CompareAndSet.set(identicalRaced,identical,"count",0,1));
    AtomicBoolean injected=new AtomicBoolean();
    Session raced=sessionProxy(second,(p,m,a)->{
      Object out=invoke(second,m,a);
      if(m.getName().equals("getNode") && a[0].equals(CapacityLedger.callerPath(quantity,caller).path()) && !injected.get()) {
        Node node=(Node)out;
        return Proxy.newProxyInstance(StoreReviewProbe.class.getClassLoader(),new Class<?>[]{Node.class},(np,nm,na)->{
          Object no=invoke(node,nm,na);
          if(nm.getName().equals("getProperty")) {
            Property prop=(Property)no;
            return Proxy.newProxyInstance(StoreReviewProbe.class.getClassLoader(),new Class<?>[]{Property.class},(pp,pm,pa)->{
              Object po=invoke(prop,pm,pa);
              if(pm.getName().equals("getLong")&&injected.compareAndSet(false,true)) CapacityLedger.release(first,quantity,caller,1,contract);
              return po;
            });
          }
          return no;
        });
      }
      return out;
    });
    CapacityLedger.release(raced, quantity, caller, 1, contract);
    System.out.println("After two racing releases total=" + CapacityLedger.held(first,quantity,contract) + " share=" + CapacityLedger.heldBy(first,quantity,caller,contract));
    var authority = ((DefaultContinuationKeyAuthority.Opened)DefaultContinuationKeyAuthority.open(first,contract)).authority();
    var ring = ((ContinuationKeyAuthority.Read)authority.establish()).ring();
    var actualLease = RotationLease.take(first,DefaultContinuationKeyAuthority.record(),"real-holder",1000,contract);
    var newRing = ((KeyRing.Held)ring.rotated(authority.material(),1001,contract)).ring();
    var fabricatedLease = new ContinuationKeyAuthority.Lease("never-took-the-lease", Long.MAX_VALUE);
    System.out.println("Actual lease=" + actualLease);
    System.out.println("Unowned lease compareAndSet=" + authority.compareAndSet(ring,newRing,fabricatedLease,1001).getClass().getSimpleName());
    var identity=identity(contract);
    var operation=OperationStore.pathOf(identity);
    byte[] commandBytes=Files.readAllBytes(Path.of("core/src/test/resources/fixtures/maintenance-sweep/command-contract.json"));
    var commandDocument=((BoundedDocumentReader.Read)BoundedDocumentReader.read(commandBytes,BoundedDocumentReader.Bounds.from(contract))).value();
    var commandContract=((CommandContractIdentity.Held)CommandContractIdentity.of(commandDocument,CommandContractIdentity.Bounds.from(contract))).identity();
    var logical=((LogicalOperation.Held)LogicalOperation.accepted(identity,rs.slingshot.agent.digest.Digest.of(new byte[0]),commandContract,caller,1000,1000,contract)).operation();
    GenerationStore.establish(first);
    walked(first,operation.path().substring(0,operation.path().lastIndexOf('/')));
    var submission=new SubmissionAdmission.Submission(identity,logical.submissionDigest(),commandContract,caller,1000);
    AtomicBoolean admissionInjected=new AtomicBoolean();
    Session admissionRace=sessionProxy(first,(p,m,a)->{
      if(m.getName().equals("save")&&admissionInjected.compareAndSet(false,true))
        System.out.println("Nested submission admission="+SubmissionAdmission.admit(second,submission,1000,contract).getClass().getSimpleName());
      return invoke(first,m,a);
    });
    System.out.println("Outer submission admission="+SubmissionAdmission.admit(admissionRace,submission,1000,contract).getClass().getSimpleName());
    AtomicBoolean startedInjected=new AtomicBoolean();
    Session startRace=sessionProxy(first,(p,m,a)->{
      if(m.getName().equals("save")&&startedInjected.compareAndSet(false,true))
        System.out.println("Nested operation start="+OperationStore.move(second,logical,OperationState.RUNNING).getClass().getSimpleName());
      return invoke(first,m,a);
    });
    System.out.println("Outer operation start="+OperationStore.move(startRace,logical,OperationState.RUNNING).getClass().getSimpleName());
    AtomicInteger saves=new AtomicInteger();
    Session fenceCrash=sessionProxy(first,(p,m,a)->{
      if(m.getName().equals("save")&&saves.incrementAndGet()==3) throw new RepositoryException("injected loss before owner save");
      return invoke(first,m,a);
    });
    try {ExecutionFence.take(fenceCrash,identity,"worker-a",1000,contract);} catch(RepositoryException expected) { System.out.println(expected.getMessage()); }
    first.refresh(false);
    System.out.println("Fence after restart has owner=" + first.getNode(ExecutionFence.pathOf(identity).path()).hasProperty(ExecutionFence.WORKER));
    System.out.println("Take after expiry="+ExecutionFence.take(first,identity,"worker-b",1000000,contract));
    first.getNode(ExecutionFence.pathOf(identity).path()).remove(); first.save();
    Node record=first.getNode(operation.path());
    record.setProperty(RetentionPolicy.REQUEST_START,0L); record.setProperty(MaintenanceSweep.CALLER,caller.name());
    Node artifact=record.addNode(ArtifactStore.NODE,"nt:unstructured").addNode("result","nt:unstructured"); artifact.setProperty(ArtifactStore.BYTE_COUNT,4L); first.save();
    ArtifactStore.prepare(first,caller); LedgerAdmission.prepare(first,caller);
    CapacityLedger.admit(first,AccountedQuantity.ARTIFACT_ROWS,caller,1,contract);
    CapacityLedger.admit(first,AccountedQuantity.ARTIFACT_BYTES,caller,4,contract);
    long past=RetentionPolicy.Kind.OPERATION_DETAIL.minimum(contract);
    Session sweepCrash=sessionProxy(first,(p,m,a)->{
      if(m.getName().equals("save")&&!first.nodeExists(operation.path())) throw new RepositoryException("injected loss before delete save");
      return invoke(first,m,a);
    });
    try {MaintenanceSweep.run(sweepCrash,identity.generation(),past,contract);} catch(RepositoryException expected) {System.out.println(expected.getMessage());}
    first.refresh(false);
    System.out.println("Interrupted sweep artifact exists="+first.nodeExists(operation.child(ArtifactStore.NODE).child("result").path())+" rows="+CapacityLedger.held(first,AccountedQuantity.ARTIFACT_ROWS,contract)+" bytes="+CapacityLedger.held(first,AccountedQuantity.ARTIFACT_BYTES,contract));
    MaintenanceSweep.run(first,identity.generation(),past,contract);
    System.out.println("Repeated sweep artifact exists="+first.nodeExists(operation.child(ArtifactStore.NODE).child("result").path())+" rows="+CapacityLedger.held(first,AccountedQuantity.ARTIFACT_ROWS,contract)+" bytes="+CapacityLedger.held(first,AccountedQuantity.ARTIFACT_BYTES,contract));
    // Two legitimate-length identifiers sharing one bucket expose the row-bound check location.
    walked(first,operation.path());
    Node bucket=first.getNode(operation.path()).getParent();
    first.getNode(operation.path()).setProperty(RetentionPolicy.REQUEST_START,past);
    String secondIdentifier=identity.identifier().rendered().substring(0,63)+(identity.identifier().rendered().endsWith("a")?"b":"a");
    bucket.addNode(secondIdentifier,"nt:unstructured").setProperty(RetentionPolicy.REQUEST_START,past); first.save();
    byte[] boundedBytes=Files.readString(Path.of("support/agent-contract.toml")).replace("maintenance_sweep_work_bound_rows = 1024","maintenance_sweep_work_bound_rows = 1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var bounded=((AgentContract.Loaded)AgentContract.load(boundedBytes,AgentContract.digestOf(boundedBytes))).contract();
    System.out.println("Sweep bound=1 actual examined="+MaintenanceSweep.run(first,identity.generation(),past,bounded).examined());
    GenerationStore.establish(first);
    SubscriptionLedger.prepare(first,caller);
    var subscribed=(SubscriptionLedger.Subscribed)SubscriptionLedger.subscribe(first,caller,"review-subscription",identity.generation(),1000,contract);
    SubscriptionLedger.end(first,caller,subscribed.record(),contract);
    SubscriptionLedger.end(first,caller,subscribed.record(),contract);
    System.out.println("Two subscription end calls rows="+CapacityLedger.held(first,AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,contract));
    AtomicInteger rotationSaves=new AtomicInteger();
    Session rotationCrash=sessionProxy(first,(p,m,a)->{
      if(m.getName().equals("save")&&rotationSaves.incrementAndGet()==2) throw new RepositoryException("injected loss before generation history save");
      return invoke(first,m,a);
    });
    var nextGeneration=((EventStoreGeneration.Held)EventStoreGeneration.of(2)).generation();
    try {GenerationRotation.rotate(rotationCrash,nextGeneration,1000,contract);} catch(RepositoryException expected) {System.out.println(expected.getMessage());}
    first.refresh(false);
    System.out.println("Interrupted rotation serving="+((GenerationStore.Held)GenerationStore.serving(first)).generation()+" served="+GenerationStore.served(first)+" access to previous="+GenerationRotation.accessTo(first,identity.generation()));
    resolver.close();
    var teardown = SlingContext.class.getDeclaredMethod("tearDownContext"); teardown.setAccessible(true); teardown.invoke(context);
  }
}

~~~
