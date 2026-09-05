# Command execution and traversal probe

Recorded review harness, not shipped product code. See [EVIDENCE.md](../EVIDENCE.md) for setup,
proof limits and expected observations. Java blocks are retained as runnable review material so
future implementation tasks can convert the counterexamples into normal regression fixtures.

## CommandReviewProbe.java

~~~java
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sling.api.resource.*;
import org.apache.sling.testing.resourceresolver.MockResourceResolverFactory;
import rs.slingshot.agent.command.*;
import rs.slingshot.agent.command.content.*;
import rs.slingshot.agent.command.page.*;
import rs.slingshot.agent.command.component.*;
import rs.slingshot.agent.command.mutation.*;
import rs.slingshot.agent.contract.*;
import rs.slingshot.agent.identity.*;
import rs.slingshot.agent.json.*;
public class CommandReviewProbe {
  static final AgentContract CONTRACT=((AgentContract.Loaded)AgentContract.load()).contract();
  static DocumentValue.Mapping doc(Object... pairs) {
    var m=new LinkedHashMap<String,DocumentValue>();
    for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i], (DocumentValue)pairs[i+1]);
    return new DocumentValue.Mapping(m);
  }
  static DocumentValue.Text text(String s) {return new DocumentValue.Text(s);}
  static CallerContext context(long discovery) {
    return new CallerContext(((AgentOperationIdentifier.Held)AgentOperationIdentifier.of("a".repeat(64),CONTRACT)).identifier(), new Budget(Budget.Kind.DISCOVERY,discovery),Budget.time(CONTRACT),new Budget(Budget.Kind.RESULT,1000000),ProgressSink.under(CONTRACT));
  }
  public static void main(String[] args) throws Exception {
    var factory = new MockResourceResolverFactory();
    try(var rr=factory.getResourceResolver(Map.of())) {
      var content=rr.create(rr.getResource("/"),"content",Map.of());
      var target=rr.create(content,"target",Map.of("jcr:primaryType","cq:Page"));
      var references=rr.create(content,"references",Map.of("link","/content/target"));
      rr.commit();
      System.out.println("references at budget 1="+RepositoryReach.pointingAt(rr,"/content/target",1).size()+"; budget 100="+RepositoryReach.pointingAt(rr,"/content/target",100).size());
      var answer=new DeletePageHandler(CONTRACT).run(doc("page_path",text("/content/target"),"reference_policy",text("refuse_when_referenced")),rr,context(1));
      System.out.println("delete with refuse_when_referenced="+answer+"; target exists="+(rr.getResource("/content/target")!=null)+"; retained link="+references.getValueMap().get("link"));
      var parent=rr.create(content,"pages",Map.of("jcr:primaryType","cq:Page"));
      for(int i=0;i<3;i++) rr.create(parent,"p"+i,Map.of("jcr:primaryType","cq:Page"));
      rr.commit();
      var initial=doc("mode",text("initial"),"offset",new DocumentValue.Whole(1),"limit",new DocumentValue.Whole(1));
      var continuation=doc("mode",text("continuation"),"continuation_token",text("not-a-real-token"));
      var list=new ListChildPagesHandler(CONTRACT);
      System.out.println("list initial offset 1 limit 1="+list.run(doc("root_path",text("/content/pages"),"result_window",initial),rr,context(100)));
      System.out.println("list invalid continuation="+list.run(doc("root_path",text("/content/pages"),"result_window",continuation),rr,context(100)));
      System.out.println("query initial offset 1 limit 1="+new QueryPathsHandler(CONTRACT).run(doc("root_path",text("/content/pages"),"result_window",initial),rr,context(100)));
      var taken=new AtomicInteger();
      var root=new ResourceWrapper(parent) {
        public Iterator<Resource> listChildren() {
          return new Iterator<>() {int next=0; public boolean hasNext(){return next<10000;} public Resource next(){next++;taken.incrementAndGet();return rr.getResource("/content/pages/p0");}};
        }
      };
      System.out.println("under bound 1 found="+RepositoryReach.under(root,1).size()+"; child iterator consumed="+taken);
      System.out.println("negative window="+ResultWindow.initial(-1,-1,CONTRACT));
      var arbitrary = rr.create(content,"ordinary-folder",Map.of("jcr:primaryType","sling:Folder"));
      for(int i=0;i<5;i++) rr.create(arbitrary,"child"+i,Map.of());
      rr.commit();
      System.out.println("delete_component ordinary folder with discovery=1="+new ComponentPathHandler(CONTRACT,ComponentPathCommand.Shape.DELETE).run(doc("component_path",text("/content/ordinary-folder")),rr,context(1))+"; folder exists="+(rr.getResource("/content/ordinary-folder")!=null));
    }
  }
}

~~~
