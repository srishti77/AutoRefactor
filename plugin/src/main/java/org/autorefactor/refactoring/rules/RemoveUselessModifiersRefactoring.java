/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013 Jean-Noël Rouvignac - initial API and implementation
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.refactoring.rules;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.autorefactor.refactoring.IJavaRefactoring;
import org.autorefactor.refactoring.Refactorings;
import org.autorefactor.util.NotImplementedException;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;

import static org.autorefactor.refactoring.ASTHelper.*;

/**
 * Remove modifiers implied by the context:
 * <ul>
 * <li><code>public</code>, <code>static</code> and <code>final</code> for
 * interfaces fields</li>
 * <li><code>public</code> and <code>abstract</code> for interfaces methods</li>
 * <li><code>final</code> for parameters in interface method declarations</li>
 * </ul>
 * <p>
 * Fix modifiers order.
 */
public class RemoveUselessModifiersRefactoring extends ASTVisitor implements
		IJavaRefactoring {

	private final class ModifierOrderComparator implements Comparator<Modifier> {

		public int compare(Modifier o1, Modifier o2) {
			final int i1 = modifierOrder.indexOf(o1.getKeyword());
			final int i2 = modifierOrder.indexOf(o2.getKeyword());
			if (i1 == -1 ) {
				throw new NotImplementedException("cannot determine order for modifier " + o1);
			}
			if ( i2 == -1) {
				throw new NotImplementedException("cannot compare modifier " + o2);
			}
			return i1 - i2;
		}

	}

	private static final List<ModifierKeyword> modifierOrder =
			Collections.unmodifiableList(Arrays.asList(
					ModifierKeyword.PUBLIC_KEYWORD,
					ModifierKeyword.PROTECTED_KEYWORD,
					ModifierKeyword.PRIVATE_KEYWORD,
					ModifierKeyword.STATIC_KEYWORD,
					ModifierKeyword.ABSTRACT_KEYWORD,
					ModifierKeyword.FINAL_KEYWORD,
					ModifierKeyword.TRANSIENT_KEYWORD,
					ModifierKeyword.VOLATILE_KEYWORD,
					ModifierKeyword.SYNCHRONIZED_KEYWORD,
					ModifierKeyword.NATIVE_KEYWORD,
					ModifierKeyword.STRICTFP_KEYWORD));

	private RefactoringContext ctx;

	public RemoveUselessModifiersRefactoring() {
		super();
	}

	public void setRefactoringContext(RefactoringContext ctx) {
		this.ctx = ctx;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean visit(FieldDeclaration node) {
		if (isInterface(node.getParent())) {
			// remove modifiers implied by the context
			boolean result = VISIT_SUBTREE;
			for (Modifier m : getModifiersOnly(node.modifiers())) {
				if (m.isPublic() || m.isStatic() || m.isFinal()) {
					this.ctx.getRefactorings().remove(m);
					result = DO_NOT_VISIT_SUBTREE;
				}
			}
			return result;
		}
		return ensureModifiersOrder(node);
	}

	private boolean isInterface(ASTNode node) {
		return node instanceof TypeDeclaration
				&& ((TypeDeclaration) node).isInterface();
	}

	@Override
	public boolean visit(MethodDeclaration node) {
		if (isInterface(node.getParent())) {
			// remove modifiers implied by the context
			return removePublicAbstractModifiers(node);
		}
		return ensureModifiersOrder(node);
	}

	@SuppressWarnings("unchecked")
	private boolean removePublicAbstractModifiers(BodyDeclaration node) {
		boolean result = VISIT_SUBTREE;
		for (Modifier m : getModifiersOnly(node.modifiers())) {
			if (m.isPublic() || m.isAbstract()) {
				this.ctx.getRefactorings().remove(m);
				result = DO_NOT_VISIT_SUBTREE;
			}
		}
		return result;
	}

	@Override
	public boolean visit(AnnotationTypeDeclaration node) {
		return ensureModifiersOrder(node);
	}

	@Override
	public boolean visit(AnnotationTypeMemberDeclaration node) {
		return removePublicAbstractModifiers(node);
	}

	@Override
	public boolean visit(EnumDeclaration node) {
		return ensureModifiersOrder(node);
	}

	@Override
	public boolean visit(TypeDeclaration node) {
		return ensureModifiersOrder(node);
	}

	@SuppressWarnings("unchecked")
	private boolean ensureModifiersOrder(BodyDeclaration node) {
		boolean result = VISIT_SUBTREE;
		final List<Modifier> modifiers = getModifiersOnly(node.modifiers());
		final List<Modifier> reorderedModifiers = new ArrayList<Modifier>(modifiers);
		Collections.sort(reorderedModifiers, new ModifierOrderComparator());
		if (!modifiers.equals(reorderedModifiers)) {
			final int startSize = getStartSize(node.modifiers(), modifiers);
			for (int i = startSize; i < reorderedModifiers.size(); i++) {
				insertAt(reorderedModifiers.get(i), i);
				result = DO_NOT_VISIT_SUBTREE;
			}
		}
		return result;
	}

	private <T> int getStartSize(List<T> initialList, final List<T> filteredList) {
		final List<T> l = new ArrayList<T>(initialList);
		l.removeAll(filteredList);
		return l.size();
	}

	private void insertAt(Modifier modifier, int index) {
		final Modifier copy = copySubtree(this.ctx.getAST(), modifier);
		this.ctx.getRefactorings().insertAt(copy, index,
				modifier.getLocationInParent(), modifier.getParent());
		this.ctx.getRefactorings().remove(modifier);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean visit(SingleVariableDeclaration node) {
		boolean result = VISIT_SUBTREE;
		if (isInterface(node.getParent().getParent())) {
			// remove useless "final" from method parameters
			for (Modifier m : getModifiersOnly(node.modifiers())) {
				if (m.isFinal()) {
					this.ctx.getRefactorings().remove(m);
					result = DO_NOT_VISIT_SUBTREE;
				}
			}
		}
		return result;
	}

	private List<Modifier> getModifiersOnly(Collection<IExtendedModifier> modifiers) {
		final List<Modifier> results = new LinkedList<Modifier>();
		for (IExtendedModifier em : modifiers) {
			if (em.isModifier()) {
				results.add((Modifier) em);
			}
		}
		return results;
	}

	public Refactorings getRefactorings(CompilationUnit astRoot) {
		astRoot.accept(this);
		return this.ctx.getRefactorings();
	}
}