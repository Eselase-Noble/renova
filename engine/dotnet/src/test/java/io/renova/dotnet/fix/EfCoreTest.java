package io.renova.dotnet.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The names Entity Framework Core has for what Entity Framework 6 called something else. */
class EfCoreTest {

    @Test
    void namespacesAndRenamedMembersFollowAndTheRestIsLeftForJudgement() {
        String after = EfCore.rename("""
                using System.Data.Entity;
                using System.Data.Entity.Core.Objects;
                using System.Data.Entity.ModelConfiguration;
                using System.Linq;

                public class ShopContext : DbContext
                {
                    public ShopContext() : base("name=Shop") { }

                    protected override void OnModelCreating(DbModelBuilder modelBuilder)
                    {
                        modelBuilder.Entity<Order>().HasRequired(o => o.Customer).WithMany(c => c.Orders).WillCascadeOnDelete(false);
                        modelBuilder.Entity<Order>().Property(o => o.Id).HasDatabaseGeneratedOption(DatabaseGeneratedOption.Identity);
                        Database.CommandTimeout = 60;
                    }

                    public int Purge() { return this.Database.ExecuteSqlCommand("delete from Orders where Total = 0"); }
                }
                """);

        assertThat(after).contains("using Microsoft.EntityFrameworkCore;\nusing Microsoft.EntityFrameworkCore.Metadata.Builders;\nusing System.Linq;",
                        "OnModelCreating(ModelBuilder modelBuilder)", ".OnDelete(DeleteBehavior.Restrict);", ".ValueGeneratedOnAdd();",
                        "this.Database.ExecuteSqlRaw(\"delete", "Database.SetCommandTimeout(60);",
                        // Not a rename: how the context finds its database, and what "required" means, changed.
                        ": base(\"name=Shop\")", "HasRequired(o => o.Customer)")
                .doesNotContain("System.Data.Entity");
    }

    @Test
    void codeThatDoesNotUseEntityFrameworkIsNotTouched() {
        String code = "using System.Data;\npublic class Report { void Run(System.Data.DataTable t) { } }\n";

        assertThat(EfCore.rename(code)).isEqualTo(code);
    }
}
