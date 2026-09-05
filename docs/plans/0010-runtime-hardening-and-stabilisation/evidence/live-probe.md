# Installed Apache Sling assembly probe

Recorded review harness, not shipped product code. See [EVIDENCE.md](../EVIDENCE.md) for setup,
proof limits and expected observations. Java blocks are retained as runnable review material so
future implementation tasks can convert the counterexamples into normal regression fixtures.

## LiveAssemblyProbe.java

~~~java
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import rs.slingshot.agent.interop.tier.*;
import rs.slingshot.agent.contract.*;
import rs.slingshot.agent.http.*;
import rs.slingshot.agent.identity.*;
import rs.slingshot.agent.json.*;

public class LiveAssemblyProbe {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]), out=Path.of(args[1]);
  String submitted=Files.readString(root.resolve("core/src/test/resources/fixtures/submit-servlet/a-submission.json"))
   .replaceAll("\"request_start_unix_milliseconds\": [0-9]+", "\"request_start_unix_milliseconds\": "+System.currentTimeMillis());
  AgentContract contract=((AgentContract.Loaded)AgentContract.load()).contract();
  DocumentValue.Mapping document=(DocumentValue.Mapping)((BoundedDocumentReader.Read)BoundedDocumentReader.read(submitted.getBytes(java.nio.charset.StandardCharsets.UTF_8),BoundedDocumentReader.Bounds.from(contract))).value();
  var provenance=DocumentProvenance.of(document.member("provenance").orElseThrow(),SubmitServlet.thisBuild(),CommandContractIdentity.Bounds.from(contract));
  var identity=OperationIdentity.of(document.member("operation").orElseThrow(),contract);
  System.out.println("Local submission provenance="+provenance.getClass().getSimpleName()+"; operation identity="+identity.getClass().getSimpleName());
  if (!(provenance instanceof DocumentProvenance.Held) || !(identity instanceof OperationIdentity.Held)) throw new IllegalStateException("invalid fixture: "+provenance+" "+identity);
  Files.writeString(out.resolve("submission.json"),submitted);
  var outcome=PublicSlingTier.start(root,"localhost/slingshot-agent-public-sling:1",root.resolve("core/target/slingshot-agent-core-0.1.0.jar"));
  System.out.println("Start="+outcome);
  if (!(outcome instanceof InteropTier.Running running)) throw new IllegalStateException("could not start");
  InteropTier tier=running.tier();
  System.out.println("address="+tier.address()+"; container="+PublicSlingTier.identifierOf(tier));
  try {
   var requests=TierRequests.open();
   var capabilities=requests.readAsAuthenticatedUser(tier.address()+"/bin/slingshot/agent/capabilities");
   Files.writeString(out.resolve("capabilities.json"),capabilities.body());
   System.out.println("capabilities status="+capabilities.statusCode()+" body="+capabilities.body());
   var response=requests.postAsAuthenticatedUser(tier.address()+"/bin/slingshot/agent/submit",submitted,"application/json");
   System.out.println("valid query_paths submission status="+response.statusCode()+" bodyLength="+response.body().length());
   var components=requests.readAsAuthenticatedUser(tier.address()+"/system/console/components.json");
   Files.writeString(out.resolve("components.json"),components.body());
   System.out.println("core bundle="+tier.bundleState(PublicSlingTier.CORE_BUNDLE)+"; components status="+components.statusCode());
  } finally { tier.stop(); System.out.println("owned container stopped"); }
 }
}

~~~
