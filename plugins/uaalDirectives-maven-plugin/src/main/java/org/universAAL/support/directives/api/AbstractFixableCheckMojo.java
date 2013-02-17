/*******************************************************************************
 * Copyright 2013 Universidad Polit�cnica de Madrid
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.universAAL.support.directives.api;

import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * @author amedrano
 *
 */
public abstract class AbstractFixableCheckMojo extends AbstractCheckMojo {

	/**
	 * @parameter expression="${directive.fix}" default-value="false"
	 */
	private boolean fix;

	
	/** {@inheritDoc} */
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		AbstractMojoExecutionException failedE = null;
		
		try {
			super.execute();
		} catch (AbstractMojoExecutionException e) {
			failedE = e;
		}
		
		if (fail && fix) {
			((APIFixableCheck) check).fix(null, getLog());
		}
		if (fail && !fix) {
				getLog().info("This plugin is able to automatically" +
						" fix the prbolem. just add  \"-D-Ddirective.fix\" to your command.");
		}
		
		if (failedE instanceof MojoExecutionException) {
			throw (MojoExecutionException) failedE;
		} else if (failedE instanceof MojoFailureException) {
			throw (MojoFailureException) failedE;
		}
	}
	
	/** {@inheritDoc} */
	@Override
	public APICheck getCheck() {
		return getFix();
	}

	public abstract APIFixableCheck getFix();
	
	
}
