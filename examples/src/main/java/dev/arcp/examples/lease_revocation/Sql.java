package dev.arcp.examples.lease_revocation;

import java.util.Set;

/** SQL classifier — sqlglot-backed in production. */
public final class Sql {

	public enum Op {
		READ, WRITE, DDL
	}

	public record StatementClass(Op op, Set<String> tables) {
	}

	private Sql() {
	}

	public static StatementClass classify(String sql) {
		// Real version: a JSqlParser walk for tables, isinstance against
		// Insert / Update / Delete / Merge / Create / Drop / AlterTable for op.
		throw new UnsupportedOperationException("classify: " + sql);
	}
}
