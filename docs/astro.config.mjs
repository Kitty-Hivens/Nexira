import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  integrations: [
    starlight({
      title: 'Aura Launcher',
      description: 'An unofficial launcher for SMARTYcraft',
      logo: {
        src: './src/assets/icon.png',
        replacesTitle: false,
      },
      social: {
        github: 'https://github.com/Kitty-Hivens/Aura-Launcher',
      },
      editLink: {
        baseUrl: 'https://github.com/Kitty-Hivens/Aura-Launcher/edit/stable/docs/',
      },
      customCss: ['./src/styles/custom.css'],
      sidebar: [
        {
          label: 'Getting Started',
          items: [
            { label: 'Introduction', slug: 'index' },
            { label: 'Installation', slug: 'installation' },
            { label: 'Troubleshooting', slug: 'troubleshooting' },
          ],
        },
        {
          label: 'For Developers',
          items: [
            { label: 'Building from Source', slug: 'dev/building' },
            { label: 'Architecture', slug: 'dev/architecture' },
          ],
        },
      ],
    }),
  ],
  site: 'https://kitty-hivens.github.io',
  base: '/Aura-Launcher',
});
