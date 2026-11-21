package com.repointelligence.architecture.domain;

/** Kind of a code symbol read from the syntax tree. */
public enum SymbolType {

	CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, CONSTRUCTOR, FIELD;

	public boolean isType() {
		return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == ANNOTATION;
	}

}
