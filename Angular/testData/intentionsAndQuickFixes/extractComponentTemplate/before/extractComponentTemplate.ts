import { Component } from '@angular/core';

import {RegistrationFormComponent} from './registration-form.component';

@Component({
 selector: 'app-root',
 imports: [RegistrationFormComponent],
 temp<caret>late: `
    <div>
      <app-registration-form [bind]="\`foo \${ bar }\`">Some text with \`foo \${test}\`</app-registration-form>
    </div>
  `,
 styleUrl: './app.component.css'
})
export class AppComponent {
  title = 'untitled3';
}
