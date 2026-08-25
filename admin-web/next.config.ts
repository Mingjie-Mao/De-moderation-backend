import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // vinext emits a self-contained runtime tree for Docker/VM deployments.
  // This keeps the production image free of build tools and dev dependencies.
  output: 'standalone',
};

export default nextConfig;
