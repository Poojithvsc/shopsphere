#!/bin/bash
# Runs inside the LocalStack container once S3 is ready (mounted into init/ready.d).
# Creates the product-image bucket so the app finds it on first boot — the docker-compose
# equivalent of the bucket creation SharedContainers does for tests.
set -e
awslocal s3 mb s3://shopsphere-product-images
echo "localstack-init: created bucket shopsphere-product-images"
