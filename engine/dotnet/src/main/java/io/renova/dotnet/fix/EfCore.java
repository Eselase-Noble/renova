package io.renova.dotnet.fix;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The part of moving from Entity Framework 6 to Entity Framework Core that is only names: namespaces, and the
 * types and members that were renamed and behave the same. What changed in meaning (how a context gets its
 * connection, required and optional relationships, initializers, raw SQL, validation, lazy loading) is not
 * touched here: it is left to the compiler's errors and the AI rule that follows.
 */
public final class EfCore {

    private static final String[][] NAMES = {
            // Longer namespaces first: each is a prefix of the next.
            {"System\\.Data\\.Entity\\.ModelConfiguration\\.Conventions", "Microsoft.EntityFrameworkCore.Metadata.Conventions"},
            {"System\\.Data\\.Entity\\.ModelConfiguration", "Microsoft.EntityFrameworkCore.Metadata.Builders"},
            {"System\\.Data\\.Entity\\.Infrastructure", "Microsoft.EntityFrameworkCore.Infrastructure"},
            {"System\\.Data\\.Entity\\.Migrations", "Microsoft.EntityFrameworkCore.Migrations"},
            {"System\\.Data\\.Entity\\.Core\\.Objects", "Microsoft.EntityFrameworkCore"},
            {"System\\.Data\\.Entity", "Microsoft.EntityFrameworkCore"},
            {"DbModelBuilder", "ModelBuilder"},
            {"DbEntityEntry", "EntityEntry"},
            {"DbContextTransaction", "IDbContextTransaction"},
            {"DbFunctions\\.Like", "EF.Functions.Like"},
            {"\\.HasDatabaseGeneratedOption\\(\\s*DatabaseGeneratedOption\\.Identity\\s*\\)", ".ValueGeneratedOnAdd()"},
            {"\\.HasDatabaseGeneratedOption\\(\\s*DatabaseGeneratedOption\\.None\\s*\\)", ".ValueGeneratedNever()"},
            {"\\.HasDatabaseGeneratedOption\\(\\s*DatabaseGeneratedOption\\.Computed\\s*\\)", ".ValueGeneratedOnAddOrUpdate()"},
            {"\\.WillCascadeOnDelete\\(\\s*false\\s*\\)", ".OnDelete(DeleteBehavior.Restrict)"},
            {"\\.WillCascadeOnDelete\\(\\s*(true)?\\s*\\)", ".OnDelete(DeleteBehavior.Cascade)"},
            {"Database\\.ExecuteSqlCommand\\(", "Database.ExecuteSqlRaw("},
            {"Database\\.CommandTimeout\\s*=\\s*([^;]+);", "Database.SetCommandTimeout($1);"},
    };

    private EfCore() {
    }

    public static String rename(String code) {
        if (!code.contains("System.Data.Entity") && !code.contains("DbModelBuilder")) {
            return code;
        }
        String result = code;
        for (String[] name : NAMES) {
            String word = name[0].startsWith("\\.") ? name[0] : "\\b" + name[0] + (name[0].endsWith("(") || name[0].endsWith(";") ? "" : "\\b");
            result = result.replaceAll(word, name[1]);
        }
        // Several old namespaces are one new one: the same using twice is a warning the old code did not have.
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String line : result.split("(?<=\\n)")) {
            String trimmed = line.strip();
            if (trimmed.matches("using\\s+[\\w.]+\\s*;") && !seen.add(trimmed.replaceAll("\\s+", " "))) {
                continue;
            }
            out.append(line);
        }
        return out.toString();
    }
}
