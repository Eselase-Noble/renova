package io.renova.dotnet.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A WCF service project that IIS hosted, as an ASP.NET Core application that hosts it with CoreWCF. */
class CoreWcfHostFixerTest {

    @TempDir
    Path dir;

    private void file(String path, String content) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String path) throws Exception {
        return Files.readString(dir.resolve(path));
    }

    @Test
    void theServicesAreHostedAtTheAddressesTheirSvcFilesHad() throws Exception {
        file("Rates.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">

                  <PropertyGroup>
                    <TargetFramework>net10.0</TargetFramework>
                    <RootNamespace>Acme.Rates</RootNamespace>
                  </PropertyGroup>

                  <ItemGroup>
                    <Content Include="RateService.svc" />
                    <Content Include="Web.config" />
                    <None Include="Web.Debug.config">
                      <DependentUpon>Web.config</DependentUpon>
                    </None>
                    <Compile Update="RateService.svc.cs">
                      <DependentUpon>RateService.svc</DependentUpon>
                    </Compile>
                  </ItemGroup>

                </Project>
                """);
        file("RateService.svc", "<%@ ServiceHost Language=\"C#\" Service=\"Acme.Rates.RateService\" CodeBehind=\"RateService.svc.cs\" %>\n");
        file("Admin/AuditService.svc", "<%@ ServiceHost Service=\"Acme.Rates.Admin.AuditService\" %>\n");
        file("Old/Gone.svc", "<%@ ServiceHost Service=\"Acme.Rates.Gone\" %>\n");
        file("IRateService.cs", """
                using System.ServiceModel;

                namespace Acme.Rates
                {
                    [ServiceContract(Namespace = "http://acme.example/rates")]
                    public interface IRateService
                    {
                        [OperationContract]
                        decimal Rate(string from, string to);
                    }
                }
                """);
        file("RateService.svc.cs", """
                using System;
                using System.ServiceModel;
                using System.ServiceModel.Activation;

                namespace Acme.Rates
                {
                    [AspNetCompatibilityRequirements(RequirementsMode = AspNetCompatibilityRequirementsMode.Allowed)]
                    [ServiceBehavior(InstanceContextMode = InstanceContextMode.PerCall)]
                    public class RateService : IRateService, IDisposable
                    {
                        public decimal Rate(string from, string to) { throw new FaultException("no"); }
                        public void Dispose() { }
                    }
                }
                """);
        file("Admin/AuditService.svc.cs", """
                namespace Acme.Rates.Admin
                {
                    [System.ServiceModel.ServiceContract]
                    public interface IAuditService { }

                    public class AuditService : IAuditService { }
                }
                """);
        file("Clients/BankClient.cs", """
                using System.ServiceModel;

                namespace Acme.Rates.Clients
                {
                    public class BankClient : ClientBase<IBank> { }
                }
                """);
        file("Web.config", """
                <?xml version="1.0" encoding="utf-8"?>
                <configuration>
                  <appSettings>
                    <add key="BaseCurrency" value="GHS" />
                  </appSettings>
                  <system.web>
                    <compilation debug="true" targetFramework="4.8" />
                  </system.web>
                  <system.serviceModel>
                    <services>
                      <service name="Acme.Rates.RateService">
                        <endpoint address="" binding="basicHttpBinding" bindingConfiguration="large" contract="Acme.Rates.IRateService" />
                        <endpoint address="secure" binding="wsHttpBinding" contract="IRateService" />
                        <endpoint address="fast" binding="netTcpBinding" contract="Acme.Rates.IRateService" />
                        <endpoint address="mex" binding="mexHttpBinding" contract="IMetadataExchange" />
                      </service>
                    </services>
                    <bindings>
                      <basicHttpBinding>
                        <binding name="large" maxReceivedMessageSize="1048576" />
                      </basicHttpBinding>
                    </bindings>
                  </system.serviceModel>
                </configuration>
                """);
        file("Web.Debug.config", "<configuration/>");
        List<String> notes = new ArrayList<>();

        assertThat(CoreWcfHostFixer.convert(dir.resolve("Rates.csproj"), "1.8.0", notes)).isTrue();

        String program = read("Program.cs");
        assertThat(program).contains("builder.Services.AddServiceModelServices();",
                "services.AddService<Acme.Rates.RateService>();",
                "services.AddServiceEndpoint<Acme.Rates.RateService, Acme.Rates.IRateService>(new BasicHttpBinding(), \"/RateService.svc\");",
                "services.AddServiceEndpoint<Acme.Rates.RateService, Acme.Rates.IRateService>(new WSHttpBinding(SecurityMode.None), \"/RateService.svc/secure\");",
                // No endpoint in Web.config: the one IIS gave it.
                "services.AddServiceEndpoint<Acme.Rates.Admin.AuditService, Acme.Rates.Admin.IAuditService>(new BasicHttpBinding(), \"/Admin/AuditService.svc\");",
                "// Not hosted: Acme.Rates.Gone", "metadata.HttpGetEnabled = true;", "public partial class Program { }")
                .doesNotContain("NetTcp", "IMetadataExchange", "IDisposable>");
        assertThat(read("RateService.svc.cs")).contains("using CoreWCF;", "[ServiceBehavior(InstanceContextMode = InstanceContextMode.PerCall)]")
                .doesNotContain("System.ServiceModel", "AspNetCompatibilityRequirements");
        assertThat(read("Admin/AuditService.svc.cs")).contains("[CoreWCF.ServiceContract]");
        // Code that calls another service is the client, which stays System.ServiceModel.
        assertThat(read("Clients/BankClient.cs")).contains("using System.ServiceModel;");
        assertThat(read("Rates.csproj")).contains("<Project Sdk=\"Microsoft.NET.Sdk.Web\">",
                        "<PackageReference Include=\"CoreWCF.Primitives\" Version=\"1.8.0\" />", "<PackageReference Include=\"CoreWCF.Http\" Version=\"1.8.0\" />")
                .doesNotContain(".svc", "Web.config", "Web.Debug.config");
        assertThat(read("App.config")).contains("<add key=\"BaseCurrency\" value=\"GHS\" />").doesNotContain("system.serviceModel", "system.web");
        assertThat(dir.resolve("RateService.svc")).doesNotExist();
        assertThat(dir.resolve("Web.config")).doesNotExist();
        assertThat(dir.resolve("Web.Debug.config")).doesNotExist();
        assertThat(String.join("\n", notes)).contains("endpoint with netTcpBinding is not hosted", "the settings of binding \"large\"",
                "Acme.Rates.Gone was not found", "Clients/BankClient.cs calls another service");
    }

    @Test
    void aWebEndpointNeedsItsOwnPackageAndRegistration() {
        Set<String> bindings = new LinkedHashSet<>(List.of("webhttpbinding"));
        String program = CoreWcfHostFixer.program(List.of(new CoreWcfHostFixer.Service("Acme.Api",
                List.of(new CoreWcfHostFixer.Endpoint("/Api.svc", "webHttpBinding", "Acme.IApi")))), bindings);

        assertThat(program).contains("builder.Services.AddServiceModelWebServices();",
                "services.AddServiceWebEndpoint<Acme.Api, Acme.IApi>(new WebHttpBinding(), \"/Api.svc\");");
        assertThat(CoreWcfHostFixer.projectFile("<Project Sdk=\"Microsoft.NET.Sdk\">\n</Project>\n", "1.8.0", bindings))
                .contains("CoreWCF.WebHttp");
        assertThat(CoreWcfHostFixer.namespaces("using System.ServiceModel;\nusing System.ServiceModel.Web;\n[WebGet]\n"))
                .isEqualTo("using CoreWCF;\nusing CoreWCF.Web;\n[WebGet]\n");
        // A Web.config with nothing for the application itself leaves no App.config behind.
        assertThat(CoreWcfHostFixer.appConfig("<configuration>\n  <system.web>\n    <compilation/>\n  </system.web>\n</configuration>\n")).isNull();
    }
}
